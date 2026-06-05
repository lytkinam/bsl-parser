package com.github._1c_syntax.bsl.parser.sdql.visitor;

import com.github._1c_syntax.bsl.parser.SDBLParser;
import com.github._1c_syntax.bsl.parser.sdql.model.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryPackageVisitor {

  private final String originalText;
  private final List<String> queryNames;
  private int mainQueryIndex = 0;
  private int inlineCounter = 0;
  private String currentQueryName = "";
  private List<InlineSubquery> currentInlineSubqueries = new ArrayList<>();

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
      // Attach inline subqueries collected from the main query
      if (!currentInlineSubqueries.isEmpty() && ast.getInlineSubqueries() == null) {
        ast.setInlineSubqueries(currentInlineSubqueries);
      }
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
    List<InlineSubquery> savedInlineSubqueries = currentInlineSubqueries;
    int savedInlineCounter = inlineCounter;

    currentInlineSubqueries = new ArrayList<>();
    inlineCounter = 0;

    QueryAst ast = new QueryAst();
    ast.setType("select");
    if (ctx.columns != null) ast.setSelect(visitSelectedFields(ctx.columns));
    if (ctx.temporaryTableName != null) {
      ast.setInto(textOf(ctx.temporaryTableName));
      currentQueryName = textOf(ctx.temporaryTableName);
    } else if (isMainQuery) {
      // Use name from NodeSplitter if available
      if (mainQueryIndex < queryNames.size()) {
        currentQueryName = queryNames.get(mainQueryIndex);
      } else {
        currentQueryName = "Результат_" + (mainQueryIndex + 1);
      }
      mainQueryIndex++;
    } else {
      // Subquery: use parent name + _SUB (will be overridden by caller if needed)
      currentQueryName = currentQueryName + "_SUB";
    }
    // inline subquery extraction happens during visitDataSources/where processing
    if (ctx.from != null) ast.setFrom(visitDataSources(ctx.from));
    if (ctx.where != null) {
      ast.setWhere(processLogicalExpression(ctx.where));
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

    if (!currentInlineSubqueries.isEmpty()) {
      ast.setInlineSubqueries(currentInlineSubqueries);
    }

    // Restore state for parent query context
    currentQueryName = savedQueryName;
    // Merge inline subqueries back to parent if this is a subquery
    if (!isMainQuery) {
      currentInlineSubqueries = savedInlineSubqueries;
      inlineCounter = savedInlineCounter;
    }
    // end of query processing

    return ast;
  }

  // ── Inline subquery extraction ──────────────────────────────────────────

  private String processLogicalExpression(SDBLParser.LogicalExpressionContext ctx) {
    return processLogicalExpression(ctx, "where");
  }

  private String processLogicalExpression(SDBLParser.LogicalExpressionContext ctx, String context) {
    String text = textOf(ctx);
    Map<String, InlineSubquery> replacements = new HashMap<>();
    collectInlineSubqueries(ctx, replacements, context);
    return applyReplacements(text, replacements);
  }

  private void collectInlineSubqueries(SDBLParser.LogicalExpressionContext ctx,
                                        Map<String, InlineSubquery> replacements) {
    collectInlineSubqueries(ctx, replacements, "where");
  }

  private void collectInlineSubqueries(SDBLParser.LogicalExpressionContext ctx,
                                        Map<String, InlineSubquery> replacements,
                                        String context) {
    for (SDBLParser.PredicateContext pred : ctx.condidions) {
      collectInlineSubqueriesFromPredicate(pred, replacements, context);
    }
  }

  private void collectInlineSubqueriesFromPredicate(SDBLParser.PredicateContext ctx,
                                                     Map<String, InlineSubquery> replacements) {
    collectInlineSubqueriesFromPredicate(ctx, replacements, "where");
  }

  private void collectInlineSubqueriesFromPredicate(SDBLParser.PredicateContext ctx,
                                                     Map<String, InlineSubquery> replacements,
                                                     String context) {
    if (ctx.inPredicate() != null) {
      SDBLParser.InPredicateContext ip = ctx.inPredicate();
      if (ip.subquery() != null) {
        InlineSubquery sub = extractInlineSubquery(ip.subquery(), context);
        replacements.put(textOf(ip.subquery()), sub);
      }
    }
    if (ctx.booleanPredicate != null) {
      collectInlineSubqueriesFromExpression(ctx.booleanPredicate, replacements);
    }
    // Handle nested logical expressions in parentheses
    for (int i = 0; i < ctx.getChildCount(); i++) {
      ParseTree child = ctx.getChild(i);
      if (child instanceof SDBLParser.LogicalExpressionContext) {
        collectInlineSubqueries((SDBLParser.LogicalExpressionContext) child, replacements, context);
      }
    }
  }

  private void collectInlineSubqueriesFromExpression(SDBLParser.ExpressionContext ctx,
                                                      Map<String, InlineSubquery> replacements) {
    if (ctx == null) return;
    if (ctx.bracketExpression() != null) {
      SDBLParser.BracketExpressionContext be = ctx.bracketExpression();
      if (be.subquery() != null) {
        InlineSubquery sub = extractInlineSubquery(be.subquery(), "select");
        replacements.put(textOf(be.subquery()), sub);
      }
    }
    for (int i = 0; i < ctx.getChildCount(); i++) {
      ParseTree child = ctx.getChild(i);
      if (child instanceof SDBLParser.ExpressionContext) {
        collectInlineSubqueriesFromExpression((SDBLParser.ExpressionContext) child, replacements);
      }
    }
  }

  private InlineSubquery extractInlineSubquery(SDBLParser.SubqueryContext ctx, String context) {
    inlineCounter++;
    String name = (currentQueryName.isEmpty() ? "Результат" : currentQueryName)
      + "_INLINE_" + inlineCounter;
    // inline subquery extracted
    InlineSubquery sub = new InlineSubquery();
    sub.setContext(context);
    sub.setName(name);
    String savedName = currentQueryName;
    sub.setQuery(visitSubquery(ctx));
    currentQueryName = savedName;
    currentInlineSubqueries.add(sub);
    return sub;
  }

  private String applyReplacements(String text, Map<String, InlineSubquery> replacements) {
    String result = text;
    for (Map.Entry<String, InlineSubquery> entry : replacements.entrySet()) {
      result = result.replace(entry.getKey(), entry.getValue().getName());
    }
    return result;
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
      sf.setText(processExpressionField(ctx.expressionField()));
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

  private String processExpressionField(SDBLParser.ExpressionFieldContext ctx) {
    String text = textOf(ctx);
    Map<String, InlineSubquery> replacements = new HashMap<>();
    if (ctx.expression() != null) {
      collectInlineSubqueriesFromExpression(ctx.expression(), replacements);
    } else if (ctx.logicalExpression() != null) {
      collectInlineSubqueries(ctx.logicalExpression(), replacements);
    }
    return applyReplacements(text, replacements);
  }

  // ── Data sources with inline subqueries in virtualTable and join conditions ──

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

  private String processVirtualTable(SDBLParser.VirtualTableContext ctx) {
    String text = textOf(ctx);
    Map<String, InlineSubquery> replacements = new HashMap<>();
    for (SDBLParser.VirtualTableParameterContext param : ctx.virtualTableParameters) {
      if (param.logicalExpression() != null) {
        collectInlineSubqueries(param.logicalExpression(), replacements, "virtualTable");
      }
    }
    return applyReplacements(text, replacements);
  }

  private JoinPart visitJoinPart(SDBLParser.JoinPartContext ctx) {
    JoinPart jp = new JoinPart();
    if (ctx.rightJoin() != null) jp.setJoinType("right");
    else if (ctx.leftJoin() != null) jp.setJoinType("left");
    else if (ctx.fullJoin() != null) jp.setJoinType("full");
    else jp.setJoinType("inner");
    jp.setSource(visitDataSource(ctx.source));
    if (ctx.condition != null) {
      jp.setCondition(processLogicalExpression(ctx.condition, "joinCondition"));
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
