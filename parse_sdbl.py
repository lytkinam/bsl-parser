#!/usr/bin/env python3
"""
Парсер SDBL через ANTLR4 (bsl-parser).
Аналог sql-query-analyzer, но через официальную грамматику 1c-syntax.

Использование:
    python parse_sdbl.py examples/example.sql examples/output_example
    python parse_sdbl.py examples/example_258.sql examples/output_258
"""

import json
import os
import sys
import csv
import re
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src", "main", "antlr"))

from antlr4 import CommonTokenStream, InputStream
from SDBLLexer import SDBLLexer
from SDBLParser import SDBLParser
from SDBLParserVisitor import SDBLParserVisitor


class SDBLQueryExtractor(SDBLParserVisitor):
    """Visitor: извлекает структуру запроса из AST."""

    def __init__(self, original_text: str = ""):
        self.original_text = original_text
        self.result = {}

    def _original_text(self, ctx):
        """Восстанавливает оригинальный текст с пробелами по позициям токенов."""
        if ctx is None:
            return ""
        start = ctx.start.start
        stop = ctx.stop.stop if ctx.stop else start
        return self.original_text[start:stop + 1]

    def visitQueryPackage(self, ctx: SDBLParser.QueryPackageContext):
        queries = []
        for q in ctx.queries():
            queries.append(self.visitQueries(q))
        return queries

    def visitQueries(self, ctx: SDBLParser.QueriesContext):
        if ctx.selectQuery():
            return self.visitSelectQuery(ctx.selectQuery())
        elif ctx.dropTableQuery():
            return self.visitDropTableQuery(ctx.dropTableQuery())
        return {"type": "unknown", "text": self._original_text(ctx)}

    def visitDropTableQuery(self, ctx: SDBLParser.DropTableQueryContext):
        name = self._original_text(ctx.temporaryTableName)
        return {
            "type": "drop",
            "name": name,
            "text": self._original_text(ctx),
        }

    def visitSelectQuery(self, ctx: SDBLParser.SelectQueryContext):
        result = {
            "type": "select",
            "text": self._original_text(ctx),
        }
        sub = ctx.subquery()
        if sub:
            result.update(self.visitSubquery(sub))
        if ctx.autoorder:
            result["autoorder"] = True
        if ctx.orders:
            result["order_by"] = self.visitOrderBy(ctx.orders)
        if ctx.totals:
            result["totals"] = self.visitTotalBy(ctx.totals)
        return result

    def visitSubquery(self, ctx: SDBLParser.SubqueryContext):
        result = {}
        if ctx.main:
            result.update(self.visitQuery(ctx.main))
        if ctx.unions:
            result["unions"] = [self.visitUnion(u) for u in ctx.unions]
        if ctx.orderBy():
            result["order_by"] = self.visitOrderBy(ctx.orderBy())
        return result

    def visitUnion(self, ctx: SDBLParser.UnionContext):
        return {
            "union_type": "union_all" if ctx.unionType.type == SDBLParser.UNION_ALL else "union",
            "query": self.visitQuery(ctx.query()),
        }

    def visitQuery(self, ctx: SDBLParser.QueryContext):
        result = {}
        if ctx.columns:
            result["select"] = self.visitSelectedFields(ctx.columns)
        if ctx.temporaryTableName:
            result["into"] = self._original_text(ctx.temporaryTableName)
        if ctx.from_:
            result["from"] = self.visitDataSources(ctx.from_)
        if ctx.where:
            result["where"] = self._original_text(ctx.where)
        if ctx.groupBy:
            result["group_by"] = [self._original_text(g) for g in ctx.groupBy]
        elif ctx.groupingSet:
            result["group_by_grouping_sets"] = [
                [self._original_text(e) for e in g.expressionList().expressionListItem()]
                for g in ctx.groupingSet
            ]
        if ctx.having:
            result["having"] = self._original_text(ctx.having)
        if ctx.forUpdate:
            result["for_update"] = self._original_text(ctx.forUpdate)
        if ctx.indexes:
            result["index_by"] = [self._original_text(i) for i in ctx.indexes]
        if ctx.indexSets:
            result["index_by_sets"] = [self._original_text(i) for i in ctx.indexSets]
        if ctx.limitations():
            result["limitations"] = self._original_text(ctx.limitations())
        return result

    def visitSelectedFields(self, ctx: SDBLParser.SelectedFieldsContext):
        return [self.visitSelectedField(f) for f in ctx.fields]

    def visitSelectedField(self, ctx: SDBLParser.SelectedFieldContext):
        alias = None
        if ctx.alias():
            alias = self._original_text(ctx.alias().name)

        if ctx.asteriskField():
            return {"field_type": "asterisk", "text": self._original_text(ctx.asteriskField()), "alias": alias}
        elif ctx.expressionField():
            return {"field_type": "expression", "text": self._original_text(ctx.expressionField()), "alias": alias}
        elif ctx.columnField():
            return {"field_type": "column", "text": self._original_text(ctx.columnField()), "alias": alias}
        elif ctx.emptyTableField():
            return {"field_type": "empty_table", "text": self._original_text(ctx.emptyTableField()), "alias": alias}
        elif ctx.inlineTableField():
            return {"field_type": "inline_table", "text": self._original_text(ctx.inlineTableField()), "alias": alias}
        return {"field_type": "unknown", "text": self._original_text(ctx), "alias": alias}

    def visitDataSources(self, ctx: SDBLParser.DataSourcesContext):
        return [self.visitDataSource(ds) for ds in ctx.tables]

    def visitDataSource(self, ctx: SDBLParser.DataSourceContext):
        result = {"joins": []}

        if ctx.table():
            result["table"] = self._original_text(ctx.table())
        elif ctx.virtualTable():
            result["virtual_table"] = self._original_text(ctx.virtualTable())
        elif ctx.parameterTable():
            result["parameter_table"] = self._original_text(ctx.parameterTable())
        elif ctx.externalDataSourceTable():
            result["external_data_source"] = self._original_text(ctx.externalDataSourceTable())
        elif ctx.subquery() and ctx.LPAREN():
            result["subquery"] = self.visitSubquery(ctx.subquery())

        if ctx.alias():
            result["alias"] = self._original_text(ctx.alias().name)

        for j in ctx.joins:
            result["joins"].append(self.visitJoinPart(j))

        return result

    def visitJoinPart(self, ctx: SDBLParser.JoinPartContext):
        join_type = "inner"
        if ctx.rightJoin():
            join_type = "right"
        elif ctx.leftJoin():
            join_type = "left"
        elif ctx.fullJoin():
            join_type = "full"

        return {
            "join_type": join_type,
            "source": self.visitDataSource(ctx.source),
            "condition": self._original_text(ctx.condition),
        }

    def visitOrderBy(self, ctx: SDBLParser.OrderByContext):
        return [self._original_text(o) for o in ctx.orders]

    def visitTotalBy(self, ctx: SDBLParser.TotalByContext):
        result = {}
        if ctx.selectedFields():
            result["fields"] = self.visitSelectedFields(ctx.selectedFields())
        result["groups"] = [self._original_text(g) for g in ctx.totalsGroups]
        return result


def parse_query(text: str) -> list:
    """Парсит один SQL-запрос через SDBLParser."""
    input_stream = InputStream(text)
    lexer = SDBLLexer(input_stream)
    tokens = CommonTokenStream(lexer)
    parser = SDBLParser(tokens)

    parser.removeErrorListeners()
    lexer.removeErrorListeners()

    tree = parser.queryPackage()
    extractor = SDBLQueryExtractor(original_text=text)
    return extractor.visit(tree)


def split_queries(text: str) -> list:
    """Разбивает пакет запросов на отдельные запросы по ; на верхнем уровне."""
    parts = []
    depth = 0
    current = []
    in_string = False

    for ch in text:
        if ch == '"':
            in_string = not in_string
        elif ch == '(' and not in_string:
            depth += 1
        elif ch == ')' and not in_string:
            depth -= 1
        elif ch == ';' and depth == 0 and not in_string:
            parts.append(''.join(current).strip())
            current = []
            continue
        current.append(ch)

    if current:
        parts.append(''.join(current).strip())

    return [p for p in parts if p]


def extract_temp_table_refs(query: dict) -> set:
    """Извлекает ссылки на временные таблицы из FROM/JOIN."""
    refs = set()
    
    def _scan_source(source):
        if not isinstance(source, dict):
            return
        table = source.get("table", "")
        if table and table.upper().startswith("ВТ_"):
            refs.add(table.upper())
        vt = source.get("virtual_table", "")
        if vt and vt.upper().startswith("ВТ_"):
            refs.add(vt.upper())
        for j in source.get("joins", []):
            _scan_source(j.get("source", {}))
    
    def _scan_query(q):
        if not isinstance(q, dict):
            return
        for src in q.get("from", []):
            _scan_source(src)
        for src in q.get("from", []):
            for j in src.get("joins", []):
                _scan_source(j.get("source", {}))
        if "subquery" in q:
            _scan_query(q["subquery"])
        for u in q.get("unions", []):
            _scan_query(u.get("query", {}))
    
    _scan_query(query)
    return refs


def build_model(queries: list, source_text: str) -> dict:
    """Строит модель, аналогичную sql-query-analyzer."""
    nodes = []
    edges = []
    temp_tables = {}

    for idx, q in enumerate(queries):
        node_id = idx

        if q.get("type") == "drop":
            node = {
                "id": node_id,
                "type": "drop_query",
                "name": q["name"],
                "text": q["text"],
            }
        elif q.get("type") == "select":
            into = q.get("into")
            name = into or f"Результат_{idx + 1}"

            node = {
                "id": node_id,
                "type": "temp_query" if into else "result",
                "name": name,
                "text": q["text"],
                "query": q,
            }

            if into:
                temp_tables[into.upper()] = node_id
        else:
            node = {
                "id": node_id,
                "type": "unknown",
                "name": f"Query_{idx + 1}",
                "text": q.get("text", ""),
            }

        nodes.append(node)

    # Строим edges (зависимости от ВТ)
    for node in nodes:
        if node["type"] != "select" and "query" not in node:
            continue
        q = node.get("query", {})
        refs = extract_temp_table_refs(q)
        for ref in refs:
            if ref in temp_tables:
                edges.append({
                    "from": temp_tables[ref],
                    "to": node["id"],
                    "from_name": ref,
                    "to_name": node["name"],
                })

    return {
        "nodes": nodes,
        "edges": edges,
        "source": source_text,
    }


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <input.sql> <output_dir>")
        sys.exit(1)

    input_path = sys.argv[1]
    output_dir = sys.argv[2]

    with open(input_path, encoding="utf-8") as f:
        source_text = f.read()

    parts = split_queries(source_text)
    print(f"Found {len(parts)} queries")

    queries = []
    for i, part in enumerate(parts):
        print(f"Parsing query {i + 1}/{len(parts)}...", end=" ")
        try:
            result = parse_query(part + ";")
            queries.extend(result)
            print("OK")
        except Exception as e:
            print(f"ERROR: {e}")
            queries.append({
                "type": "error",
                "text": part,
                "error": str(e),
            })

    model = build_model(queries, source_text)

    os.makedirs(output_dir, exist_ok=True)

    # JSON
    with open(os.path.join(output_dir, "model.json"), "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False, indent=2)

    # CSV nodes
    with open(os.path.join(output_dir, "nodes.csv"), "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "type", "name", "text"])
        for node in model["nodes"]:
            writer.writerow([
                node["id"],
                node.get("type", ""),
                node.get("name", ""),
                node.get("text", "")[:200],
            ])

    # CSV edges
    with open(os.path.join(output_dir, "edges.csv"), "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["from", "to", "from_name", "to_name"])
        for edge in model["edges"]:
            writer.writerow([
                edge["from"],
                edge["to"],
                edge["from_name"],
                edge["to_name"],
            ])

    # Query texts (как в sql-query-analyzer)
    qt_dir = os.path.join(output_dir, "query_texts")
    os.makedirs(qt_dir, exist_ok=True)
    for node in model["nodes"]:
        nid = node["id"]
        text = node.get("text", "")
        with open(os.path.join(qt_dir, f"node_{nid}.sql"), "w", encoding="utf-8") as f:
            f.write(f"-- node_id: {nid}\n")
            f.write(f"-- name: {node.get('name', '')}\n")
            f.write(f"-- type: {node.get('type', '')}\n")
            f.write(text)
            f.write("\n")

    print(f"\nDone. Output: {output_dir}")
    print(f"Nodes: {len(model['nodes'])}")
    print(f"Edges: {len(model['edges'])}")


if __name__ == "__main__":
    main()
