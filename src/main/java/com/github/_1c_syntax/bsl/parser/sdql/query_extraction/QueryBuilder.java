package com.github._1c_syntax.bsl.parser.sdql.query_extraction;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryAst;
import com.github._1c_syntax.bsl.parser.sdql.model.SelectField;
import com.github._1c_syntax.bsl.parser.sdql.model.WhereSubquery;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.RestoredJoin;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.RestoredQueryNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryBuilder {

  private final Map<Integer, FullParsNode> fullParsById;

  public QueryBuilder(Map<Integer, FullParsNode> fullParsById) {
    this.fullParsById = fullParsById;
  }

  public RestoredQueryNode build(FullParsNode fpNode) {
    RestoredQueryNode result = new RestoredQueryNode();
    result.setId(fpNode.getId());
    result.setName(fpNode.getName());
    result.setType(fpNode.getType());
    result.setInto(fpNode.getInto());
    result.setLimitations(fpNode.getLimitations());
    result.setOrderByFields(fpNode.getOrderBy() != null ? new ArrayList<>(fpNode.getOrderBy()) : new ArrayList<>());

    // SELECT expressions
    if (fpNode.getSelect() != null) {
      for (FullParsSelectField sf : fpNode.getSelect()) {
        String expr = buildSelectExpression(sf);
        result.getSelectExpressions().add(expr);
      }
    }

    // FROM + JOINs + inline subqueries
    if (fpNode.getFrom() != null) {
      for (DataSource ds : fpNode.getFrom()) {
        if (ds.getSubquery() != null) {
          String subqueryName = (String) ds.getSubquery();
          FullParsNode subNode = findSubQueryNode(fpNode, subqueryName);
          if (subNode != null) {
            RestoredQueryNode inlineSub = build(subNode);
            result.getInlineSubqueries().put(subqueryName, inlineSub);
          }
        }
        result.getFrom().add(ds);
        extractJoins(ds, result);
      }
    }

    // WHERE — from WhereBlock
    if (fpNode.getWhere() != null && fpNode.getWhere().getText() != null) {
      result.getWhereConditions().add(fpNode.getWhere().getText());
      // Build whereSubqueries from WhereBlock.subqueryIds
      if (fpNode.getWhere().getSubqueryIds() != null) {
        for (int subId : fpNode.getWhere().getSubqueryIds()) {
          FullParsNode subNode = fullParsById.get(subId);
          if (subNode != null) {
            RestoredQueryNode inlineSub = build(subNode);
            result.getWhereSubqueries().put(subNode.getName(), inlineSub);
          }
        }
      }
    }

    // GROUP BY — primary field (array of strings)
    if (fpNode.getGroupBy() != null) {
      result.getGroupByFields().addAll(fpNode.getGroupBy());
    }

    // HAVING — from HavingBlock
    if (fpNode.getHaving() != null && fpNode.getHaving().getText() != null) {
      result.getHavingConditions().add(fpNode.getHaving().getText());
      // Build havingSubqueries from HavingBlock.subqueryIds
      if (fpNode.getHaving().getSubqueryIds() != null) {
        for (int subId : fpNode.getHaving().getSubqueryIds()) {
          FullParsNode subNode = fullParsById.get(subId);
          if (subNode != null) {
            RestoredQueryNode inlineSub = build(subNode);
            result.getWhereSubqueries().put(subNode.getName(), inlineSub);
          }
        }
      }
    }

    // VT subqueries from DataSource.virtualTable.subqueryIds
    if (fpNode.getFrom() != null) {
      for (DataSource ds : fpNode.getFrom()) {
        if (ds.getVirtualTable() != null && ds.getVirtualTable().getSubqueryIds() != null) {
          for (int subId : ds.getVirtualTable().getSubqueryIds()) {
            FullParsNode subNode = fullParsById.get(subId);
            if (subNode != null) {
              RestoredQueryNode inlineSub = build(subNode);
              result.getVtSubqueries().put(subNode.getName(), inlineSub);
            }
          }
        }
      }
    }

    // VT inline subqueries embedded in virtualTable.subqueries (e.g. ВТ_Подзапрос_1)
    extractVtSubqueriesFromDataSources(fpNode.getFrom(), result);

    // UNION parts
    if (fpNode.getUnionNodesIds() != null && !fpNode.getUnionNodesIds().isEmpty()) {
      for (int unionId : fpNode.getUnionNodesIds()) {
        FullParsNode unionNode = fullParsById.get(unionId);
        if (unionNode != null) {
          RestoredQueryNode unionPart = build(unionNode);
          result.getUnionParts().add(unionPart);
        }
      }
      if (fpNode.getUnionType() != null) {
        result.setUnionType(fpNode.getUnionType());
      } else if (!result.getUnionParts().isEmpty()) {
        result.setUnionType("union_all");
      }
    }

    return result;
  }

  private FullParsNode findSubQueryNode(FullParsNode parentNode, String subqueryName) {
    if (parentNode.getSubqueryIds() == null) return null;
    for (int subId : parentNode.getSubqueryIds()) {
      FullParsNode candidate = fullParsById.get(subId);
      if (candidate != null && subqueryName.equals(candidate.getName())) {
        return candidate;
      }
    }
    return null;
  }

  private String buildSelectExpression(FullParsSelectField sf) {
    String text = sf.getText() != null ? sf.getText() : "";
    String alias = sf.getAlias() != null ? sf.getAlias() : "";
    if (text.equals(alias)) {
      return alias;
    }
    return text + " КАК " + alias;
  }

  /**
   * Extract VT inline subqueries from virtualTable.subqueries (embedded QueryAst).
   */
  private void extractVtSubqueriesFromDataSources(List<DataSource> sources, RestoredQueryNode result) {
    if (sources == null) return;
    for (DataSource ds : sources) {
      if (ds.getVirtualTable() != null && ds.getVirtualTable().getSubqueries() != null) {
        for (WhereSubquery ws : ds.getVirtualTable().getSubqueries()) {
          if (ws.getName() != null && ws.getQuery() != null) {
            RestoredQueryNode inlineSub = buildFromQueryAst(ws.getQuery(), ws.getName());
            result.getVtSubqueries().put(ws.getName(), inlineSub);
          }
        }
      }
      // Also check nested joins
      if (ds.getJoins() != null) {
        for (JoinPart jp : ds.getJoins()) {
          if (jp.getSource() != null && jp.getSource().getVirtualTable() != null
              && jp.getSource().getVirtualTable().getSubqueries() != null) {
            for (WhereSubquery ws : jp.getSource().getVirtualTable().getSubqueries()) {
              if (ws.getName() != null && ws.getQuery() != null) {
                RestoredQueryNode inlineSub = buildFromQueryAst(ws.getQuery(), ws.getName());
                result.getVtSubqueries().put(ws.getName(), inlineSub);
              }
            }
          }
        }
      }
    }
  }

  /**
   * Build RestoredQueryNode from embedded QueryAst (used for virtualTable.subqueries).
   */
  private RestoredQueryNode buildFromQueryAst(QueryAst ast, String name) {
    RestoredQueryNode result = new RestoredQueryNode();
    result.setName(name);
    result.setType(ast.getType() != null ? ast.getType() : "sub_query");
    result.setInto(ast.getInto());
    result.setLimitations(ast.getLimitations());
    if (ast.getOrderBy() != null) {
      result.setOrderByFields(new ArrayList<>(ast.getOrderBy()));
    }

    // SELECT expressions
    if (ast.getSelect() != null) {
      for (SelectField sf : ast.getSelect()) {
        String text = sf.getText() != null ? sf.getText() : "";
        String alias = sf.getAlias() != null ? sf.getAlias() : "";
        if (alias.isEmpty()) {
          result.getSelectExpressions().add(text);
        } else {
          result.getSelectExpressions().add(text + " КАК " + alias);
        }
      }
    }

    // FROM + nested VT subqueries
    if (ast.getFrom() != null) {
      for (DataSource ds : ast.getFrom()) {
        result.getFrom().add(ds);
      }
      extractVtSubqueriesFromDataSources(ast.getFrom(), result);
    }

    // WHERE
    if (ast.getWhere() != null && ast.getWhere().getText() != null) {
      result.getWhereConditions().add(ast.getWhere().getText());
    }

    // GROUP BY
    if (ast.getGroupBy() != null) {
      result.getGroupByFields().addAll(ast.getGroupBy());
    }

    // HAVING
    if (ast.getHaving() != null && ast.getHaving().getText() != null) {
      result.getHavingConditions().add(ast.getHaving().getText());
    }

    return result;
  }

  private void extractJoins(DataSource ds, RestoredQueryNode result) {
    if (ds.getJoins() == null) return;
    for (JoinPart jp : ds.getJoins()) {
      RestoredJoin rj = new RestoredJoin();
      rj.setJoinType(jp.getJoinType());
      rj.setCondition(jp.getCondition());

      DataSource src = jp.getSource();
      if (src != null) {
        String sourceTable;
        if (src.getTable() != null) {
          sourceTable = src.getTable();
        } else if (src.getVirtualTable() != null) {
          sourceTable = src.getVirtualTable().getText();
        } else if (src.getSubquery() != null) {
          sourceTable = (String) src.getSubquery();
        } else if (src.getExternalDataSource() != null) {
          sourceTable = src.getExternalDataSource();
        } else if (src.getParameterTable() != null) {
          sourceTable = src.getParameterTable();
        } else {
          sourceTable = "?";
        }
        rj.setSourceTable(sourceTable);
        rj.setAlias(src.getAlias() != null ? src.getAlias() : sourceTable);
      }

      result.getJoins().add(rj);
    }
  }
}
