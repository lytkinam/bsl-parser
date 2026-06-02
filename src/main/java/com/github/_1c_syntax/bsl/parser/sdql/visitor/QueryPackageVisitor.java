package com.github._1c_syntax.bsl.parser.sdql.visitor;

import com.github._1c_syntax.bsl.parser.SDBLParser;
import com.github._1c_syntax.bsl.parser.sdql.model.*;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.List;

public class QueryPackageVisitor {

  private final String originalText;

  public QueryPackageVisitor(String originalText) {
    this.originalText = originalText;
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
    if (ctx.selectQuery() != null) {
      return visitSelectQuery(ctx.selectQuery());
    } else if (ctx.dropTableQuery() != null) {
      QueryAst ast = new QueryAst();
      ast.setType("drop");
      ast.setInto(textOf(ctx.dropTableQuery().temporaryTableName));
      return ast;
    }
    QueryAst ast = new QueryAst();
    ast.setType("unknown");
    return ast;
  }

  private QueryAst visitSelectQuery(SDBLParser.SelectQueryContext ctx) {
    QueryAst ast = new QueryAst();
    ast.setType("select");
    SDBLParser.SubqueryContext sub = ctx.subquery();
    if (sub != null) {
      ast = visitSubquery(sub);
    }
    if (ctx.autoorder != null) ast.setAutoorder(true);
    if (ctx.orders != null) ast.setOrderBy(visitOrderBy(ctx.orders));
    if (ctx.totals != null) ast.setTotals(visitTotalBy(ctx.totals));
    return ast;
  }

  private QueryAst visitSubquery(SDBLParser.SubqueryContext ctx) {
    QueryAst ast = visitQuery(ctx.main);
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
    up.setQuery(visitQuery(ctx.query()));
    return up;
  }

  private QueryAst visitQuery(SDBLParser.QueryContext ctx) {
    QueryAst ast = new QueryAst();
    ast.setType("select");
    if (ctx.columns != null) ast.setSelect(visitSelectedFields(ctx.columns));
    if (ctx.temporaryTableName != null) ast.setInto(textOf(ctx.temporaryTableName));
    if (ctx.from != null) ast.setFrom(visitDataSources(ctx.from));
    if (ctx.where != null) ast.setWhere(textOf(ctx.where));
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
    if (ctx.having != null) ast.setHaving(textOf(ctx.having));
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
    return ast;
  }

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
      sf.setText(textOf(ctx.expressionField()));
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
      ds.setVirtualTable(textOf(ctx.virtualTable()));
    } else if (ctx.parameterTable() != null) {
      ds.setParameterTable(textOf(ctx.parameterTable()));
    } else if (ctx.externalDataSourceTable() != null) {
      ds.setExternalDataSource(textOf(ctx.externalDataSourceTable()));
    } else if (ctx.subquery() != null && ctx.LPAREN() != null) {
      ds.setSubquery(visitSubquery(ctx.subquery()));
    }
    if (ctx.alias() != null && ctx.alias().name != null) {
      ds.setAlias(textOf(ctx.alias().name));
    }
    for (SDBLParser.JoinPartContext j : ctx.joins) {
      ds.getJoins().add(visitJoinPart(j));
    }
    return ds;
  }

  private JoinPart visitJoinPart(SDBLParser.JoinPartContext ctx) {
    JoinPart jp = new JoinPart();
    if (ctx.rightJoin() != null) jp.setJoinType("right");
    else if (ctx.leftJoin() != null) jp.setJoinType("left");
    else if (ctx.fullJoin() != null) jp.setJoinType("full");
    else jp.setJoinType("inner");
    jp.setSource(visitDataSource(ctx.source));
    jp.setCondition(textOf(ctx.condition));
    return jp;
  }

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
