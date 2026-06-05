package com.github._1c_syntax.bsl.parser.sdql.visitor;

import com.github._1c_syntax.bsl.parser.SDBLParser;
import com.github._1c_syntax.bsl.parser.sdql.model.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

public class QueryPackageVisitor {

  private final String originalText;
  private final List<String> queryNames;
  private int mainQueryIndex = 0;
  private int vtCounter = 0;
  private String currentQueryName = "";

  public QueryPackageVisitor(String originalText) {
    this(originalText, List.of());
  }

  public QueryPackageVisitor(String originalText, List<String> queryNames) {
    this.originalText = originalText;
    this.queryNames = queryNames;
  }

  private String textOf(ParserRuleContext ctx) {
    if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) return "";
    return originalText.substring(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex() + 1);
  }

  public List<QueryAst> visitQueryPackage(SDBLParser.QueryPackageContext ctx) {
    List<QueryAst> result = new ArrayList<>();
    for (SDBLParser.QueriesContext q : ctx.queries()) {
      result.add(visitQueries(q));
    }
    return result;
  }

  private QueryAst visitQueries(SDBLParser.QueriesContext ctx) {
    QueryAst ast;
    if (ctx.selectQuery() != null) {
      ast = visitSelectQuery(ctx.selectQuery());
    } else if (ctx.dropTableQuery() != null) {
      ast = new QueryAst();
      ast.setType("drop");
      ast.setInto(textOf(ctx.dropTableQuery().temporaryTableName));
    } else {
      ast = new QueryAst();
      ast.setType("unknown");
    }
    if (ctx.getStart() != null && ctx.getStop() != null) {
      ast.setStartIndex(ctx.getStart().getStartIndex());
      ast.setEndIndex(ctx.getStop().getStopIndex() + 1);
    }
    return ast;
  }

  private QueryAst visitSelectQuery(SDBLParser.SelectQueryContext ctx) {
    QueryAst ast = new QueryAst();
    ast.setType("select");
    SDBLParser.SubqueryContext sub = ctx.subquery();
    if (sub != null) {
      ast = visitSubquery(sub, true);
    }
    if (ctx.autoorder != null) ast.setAutoorder(true);
    if (ctx.orders != null) ast.setOrderBy(visitOrderBy(ctx.orders));
    if (ctx.totals != null) ast.setTotals(visitTotalBy(ctx.totals));
    return ast;
  }

  private QueryAst visitSubquery(SDBLParser.SubqueryContext ctx) {
    return visitSubquery(ctx, false);
  }

  private QueryAst visitSubquery(SDBLParser.SubqueryContext ctx, boolean isMainQuery) {
    QueryAst ast = visitQuery(ctx.main, isMainQuery);
    if (ctx.unions != null && !ctx.unions.isEmpty()) {
      List<UnionPart> unions = new ArrayList<>();
      for (SDBLParser.UnionContext u : ctx.unions) {
        unions.add(visitUnion(u));
      }
      ast.setUnions(unions);
    }
    if (ctx.orderBy() != null) {
      ast.setOrderBy(visitOrderBy(ctx.orderBy()));
    }
    return ast;
  }

  private UnionPart visitUnion(SDBLParser.UnionContext ctx) {
    UnionPart up = new UnionPart();
    up.setUnionType(ctx.unionType.getType() == SDBLParser.UNION_ALL ? "union_all" : "union");
    up.setQuery(visitQuery(ctx.query(), false));
    return up;
  }

  private QueryAst visitQuery(SDBLParser.QueryContext ctx) {
    return visitQuery(ctx, true);
  }

  private QueryAst visitQuery(SDBLParser.QueryContext ctx, boolean isMainQuery) {
    String savedQueryName = currentQueryName;
    int savedVtCounter = vtCounter;

    vtCounter = 0;

    QueryAst ast = new QueryAst();
    ast.setType("select");
    if (ctx.columns != null) ast.setSelect(visitSelectedFields(ctx.columns));
    if (ctx.temporaryTableName != null) {
      ast.setInto(textOf(ctx.temporaryTableName));
      currentQueryName = textOf(ctx.temporaryTableName);
    } else if (isMainQuery) {
      if (mainQueryIndex < queryNames.size()) {
        currentQueryName = queryNames.get(mainQueryIndex);
      } else {
        currentQueryName = "Результат_" + (mainQueryIndex + 1);
      }
      mainQueryIndex++;
    } else {
      currentQueryName = currentQueryName + "_SUB";
    }
    if (ctx.from != null) ast.setFrom(visitDataSources(ctx.from));
    if (ctx.where != null) {
      ast.setWhere(processWhere(ctx.where));
    }
    if (ctx.groupBy != null && !ctx.groupBy.isEmpty()) {
      List<String> gb = new ArrayList<>();
      for (var g : ctx.groupBy) gb.add(textOf(g));
      ast.setGroupBy(gb);
    } else if (ctx.groupingSet != null && !ctx.groupingSet.isEmpty()) {
      List<List<String>> sets = new ArrayList<>();
      for (var g : ctx.groupingSet) {
        List<String> items = new ArrayList<>();
        for (var e : g.expressionListItem()) {
          items.add(textOf(e));
        }
        sets.add(items);
      }
      ast.setGroupByGroupingSets(sets);
    }
    if (ctx.having != null) ast.setHaving(processHaving(ctx.having));
    if (ctx.forUpdate != null) ast.setForUpdate(textOf(ctx.forUpdate));
    if (ctx.indexes != null && !ctx.indexes.isEmpty()) {
      List<String> idx = new ArrayList<>();
      for (var i : ctx.indexes) idx.add(textOf(i));
      ast.setIndexBy(idx);
    }
    if (ctx.indexSets != null && !ctx.indexSets.isEmpty()) {
      List<String> idx = new ArrayList<>();
      for (var i : ctx.indexSets) idx.add(textOf(i));
      ast.setIndexBySets(idx);
    }
    if (ctx.limitations() != null) ast.setLimitations(textOf(ctx.limitations()));

    currentQueryName = savedQueryName;
    if (!isMainQuery) {
      vtCounter = savedVtCounter;
    }
    return ast;
  }

  // ── Where / Having blocks ───────────────────────────────────────────────

  private WhereBlock processWhere(SDBLParser.LogicalExpressionContext ctx) {
    return buildWhereBlock(ctx);
  }

  private HavingBlock processHaving(SDBLParser.LogicalExpressionContext ctx) {
    WhereBlock wb = buildWhereBlock(ctx);
    HavingBlock hb = new HavingBlock();
    hb.setText(wb.getText());
    hb.setSubqueries(wb.getSubqueries());
    return hb;
  }

  private WhereBlock buildWhereBlock(SDBLParser.LogicalExpressionContext ctx) {
    List<SubqueryEntry> entries = collectSubqueries(ctx);
    WhereBlock block = new WhereBlock();
    if (entries.isEmpty()) {
      block.setText(textOf(ctx));
      return block;
    }
    String replacedText = replaceSubqueriesByPosition(ctx, entries);
    block.setText(replacedText);
    List<WhereSubquery> subqueries = new ArrayList<>();
    for (SubqueryEntry entry : entries) {
      WhereSubquery ws = new WhereSubquery();
      ws.setName(entry.alias);
      String savedName = currentQueryName;
      ws.setQuery(visitSubquery(entry.subqueryCtx));
      currentQueryName = savedName;
      subqueries.add(ws);
    }
    block.setSubqueries(subqueries);
    return block;
  }

  // ── Subquery collection and replacement ─────────────────────────────────

  private static class SubqueryEntry {
    SDBLParser.SubqueryContext subqueryCtx;
    String alias;
  }

  private List<SubqueryEntry> collectSubqueries(SDBLParser.LogicalExpressionContext ctx) {
    List<SubqueryEntry> result = new ArrayList<>();
    collectSubqueriesFromLogical(ctx, result);
    return result;
  }

  private void collectSubqueriesFromLogical(SDBLParser.LogicalExpressionContext ctx,
                                             List<SubqueryEntry> result) {
    for (SDBLParser.PredicateContext pred : ctx.condidions) {
      collectSubqueriesFromPredicate(pred, result);
    }
    for (int i = 0; i < ctx.getChildCount(); i++) {
      ParseTree child = ctx.getChild(i);
      if (child instanceof SDBLParser.LogicalExpressionContext) {
        collectSubqueriesFromLogical((SDBLParser.LogicalExpressionContext) child, result);
      }
    }
  }

  private void collectSubqueriesFromPredicate(SDBLParser.PredicateContext ctx,
                                               List<SubqueryEntry> result) {
    if (ctx.inPredicate() != null) {
      SDBLParser.InPredicateContext ip = ctx.inPredicate();
      if (ip.subquery() != null) {
        SubqueryEntry entry = new SubqueryEntry();
        entry.subqueryCtx = ip.subquery();
        result.add(entry);
      }
    }
    if (ctx.booleanPredicate != null) {
      collectSubqueriesFromExpression(ctx.booleanPredicate, result);
    }
  }

  private void collectSubqueriesFromExpression(SDBLParser.ExpressionContext ctx,
                                                List<SubqueryEntry> result) {
    if (ctx == null) return;
    if (ctx.bracketExpression() != null) {
      SDBLParser.BracketExpressionContext be = ctx.bracketExpression();
      if (be.subquery() != null) {
        SubqueryEntry entry = new SubqueryEntry();
        entry.subqueryCtx = be.subquery();
        result.add(entry);
      }
    }
    for (int i = 0; i < ctx.getChildCount(); i++) {
      ParseTree child = ctx.getChild(i);
      if (child instanceof SDBLParser.ExpressionContext) {
        collectSubqueriesFromExpression((SDBLParser.ExpressionContext) child, result);
      }
    }
  }

  private String replaceSubqueriesByPosition(ParserRuleContext parentCtx,
                                              List<SubqueryEntry> entries) {
    String fullText = textOf(parentCtx);
    StringBuilder sb = new StringBuilder(fullText);
    int offset = 0;
    for (SubqueryEntry entry : entries) {
      vtCounter++;
      String alias = "ВТ_Подзапрос_" + vtCounter;
      entry.alias = alias;
      String subText = textOf(entry.subqueryCtx);
      int start = entry.subqueryCtx.getStart().getStartIndex() - parentCtx.getStart().getStartIndex();
      int end = entry.subqueryCtx.getStop().getStopIndex() - parentCtx.getStart().getStartIndex() + 1;
      sb.replace(start + offset, end + offset, alias);
      offset += alias.length() - subText.length();
    }
    return sb.toString();
  }

  // ── Selected fields with inline subqueries ──────────────────────────────

  private List<SelectField> visitSelectedFields(SDBLParser.SelectedFieldsContext ctx) {
    List<SelectField> list = new ArrayList<>();
    for (SDBLParser.SelectedFieldContext f : ctx.fields) {
      list.add(visitSelectedField(f));
    }
    return list;
  }

  private SelectField visitSelectedField(SDBLParser.SelectedFieldContext ctx) {
    SelectField sf = new SelectField();
    if (ctx.alias() != null && ctx.alias().name != null) {
      sf.setAlias(textOf(ctx.alias().name));
    }
    if (ctx.asteriskField() != null) {
      sf.setFieldType("asterisk");
      sf.setText(textOf(ctx.asteriskField()));
    } else if (ctx.expressionField() != null) {
      sf.setFieldType("expression");
      sf.setText(processExpressionField(ctx.expressionField(), sf));
    } else if (ctx.columnField() != null) {
      sf.setFieldType("column");
      sf.setText(textOf(ctx.columnField()));
    } else if (ctx.emptyTableField() != null) {
      sf.setFieldType("empty_table");
      sf.setText(textOf(ctx.emptyTableField()));
    } else if (ctx.inlineTableField() != null) {
      sf.setFieldType("inline_table");
      sf.setText(textOf(ctx.inlineTableField()));
    } else {
      sf.setFieldType("unknown");
      sf.setText(textOf(ctx));
    }
    return sf;
  }

  private String processExpressionField(SDBLParser.ExpressionFieldContext ctx, SelectField sf) {
    if (ctx.expression() != null) {
      List<SubqueryEntry> entries = new ArrayList<>();
      collectSubqueriesFromExpression(ctx.expression(), entries);
      if (!entries.isEmpty()) {
        String replaced = replaceSubqueriesByPosition(ctx, entries);
        WhereSubquery ws = new WhereSubquery();
        ws.setName(entries.get(0).alias);
        String savedName = currentQueryName;
        ws.setQuery(visitSubquery(entries.get(0).subqueryCtx));
        currentQueryName = savedName;
        sf.setInlineSubquery(ws);
        return replaced;
      }
    } else if (ctx.logicalExpression() != null) {
      WhereBlock wb = buildWhereBlock(ctx.logicalExpression());
      if (wb.getSubqueries() != null && !wb.getSubqueries().isEmpty()) {
        sf.setInlineSubquery(wb.getSubqueries().get(0));
        return wb.getText();
      }
    }
    return textOf(ctx);
  }

  // ── Data sources ────────────────────────────────────────────────────────

  private List<DataSource> visitDataSources(SDBLParser.DataSourcesContext ctx) {
    List<DataSource> list = new ArrayList<>();
    for (SDBLParser.DataSourceContext ds : ctx.tables) {
      list.add(visitDataSource(ds));
    }
    return list;
  }

  private DataSource visitDataSource(SDBLParser.DataSourceContext ctx) {
    DataSource ds = new DataSource();
    ds.setJoins(new ArrayList<>());
    if (ctx.table() != null) {
      ds.setTable(textOf(ctx.table()));
    } else if (ctx.virtualTable() != null) {
      ds.setVirtualTable(processVirtualTable(ctx.virtualTable()));
    } else if (ctx.parameterTable() != null) {
      ds.setParameterTable(textOf(ctx.parameterTable()));
    } else if (ctx.externalDataSourceTable() != null) {
      ds.setExternalDataSource(textOf(ctx.externalDataSourceTable()));
    } else if (ctx.subquery() != null && ctx.LPAREN() != null) {
      String savedName = currentQueryName;
      ds.setSubquery(visitSubquery(ctx.subquery()));
      currentQueryName = savedName;
    }
    if (ctx.alias() != null && ctx.alias().name != null) {
      ds.setAlias(textOf(ctx.alias().name));
    }
    for (SDBLParser.JoinPartContext j : ctx.joins) {
      ds.getJoins().add(visitJoinPart(j));
    }
    return ds;
  }

  private VirtualTableBlock processVirtualTable(SDBLParser.VirtualTableContext ctx) {
    List<SubqueryEntry> entries = new ArrayList<>();
    for (SDBLParser.VirtualTableParameterContext param : ctx.virtualTableParameters) {
      if (param.logicalExpression() != null) {
        collectSubqueriesFromLogical(param.logicalExpression(), entries);
      }
    }
    VirtualTableBlock block = new VirtualTableBlock();
    if (entries.isEmpty()) {
      block.setText(textOf(ctx));
      return block;
    }
    String replacedText = replaceSubqueriesByPosition(ctx, entries);
    block.setText(replacedText);
    List<WhereSubquery> subqueries = new ArrayList<>();
    for (SubqueryEntry entry : entries) {
      WhereSubquery ws = new WhereSubquery();
      ws.setName(entry.alias);
      String savedName = currentQueryName;
      ws.setQuery(visitSubquery(entry.subqueryCtx));
      currentQueryName = savedName;
      subqueries.add(ws);
    }
    block.setSubqueries(subqueries);
    return block;
  }

  private JoinPart visitJoinPart(SDBLParser.JoinPartContext ctx) {
    JoinPart jp = new JoinPart();
    if (ctx.rightJoin() != null) jp.setJoinType("right");
    else if (ctx.leftJoin() != null) jp.setJoinType("left");
    else if (ctx.fullJoin() != null) jp.setJoinType("full");
    else jp.setJoinType("inner");
    jp.setSource(visitDataSource(ctx.source));
    if (ctx.condition != null) {
      WhereBlock wb = buildWhereBlock(ctx.condition);
      jp.setCondition(wb.getText());
      jp.setConditionSubqueries(wb.getSubqueries());
    }
    return jp;
  }

  // ── Order by and totals ────────────────────────────────────────────────

  private List<String> visitOrderBy(SDBLParser.OrderByContext ctx) {
    List<String> list = new ArrayList<>();
    for (var o : ctx.orders) list.add(textOf(o));
    return list;
  }

  private TotalBy visitTotalBy(SDBLParser.TotalByContext ctx) {
    TotalBy tb = new TotalBy();
    if (ctx.selectedFields() != null) {
      tb.setFields(visitSelectedFields(ctx.selectedFields()));
    }
    List<String> groups = new ArrayList<>();
    for (var g : ctx.totalsGroups) groups.add(textOf(g));
    tb.setGroups(groups);
    return tb;
  }
}
