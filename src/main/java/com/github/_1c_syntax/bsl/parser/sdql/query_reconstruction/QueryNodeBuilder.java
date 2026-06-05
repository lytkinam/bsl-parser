package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryAst;
import com.github._1c_syntax.bsl.parser.sdql.model.SelectField;
import com.github._1c_syntax.bsl.parser.sdql.model.WhereSubquery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryNodeBuilder {

  private final Map<Integer, FullParsNode> fflById;
  private final Map<Integer, FullParsNode> fullParsById;

  public QueryNodeBuilder(Map<Integer, FullParsNode> fflById) {
    this(fflById, fflById);
  }

  public QueryNodeBuilder(Map<Integer, FullParsNode> fflById, Map<Integer, FullParsNode> fullParsById) {
    this.fflById = fflById;
    this.fullParsById = fullParsById;
  }

  public RestoredQueryNode build(FullParsNode fflNode) {
    RestoredQueryNode result = new RestoredQueryNode();
    result.setId(fflNode.getId());
    result.setName(fflNode.getName());
    result.setType(fflNode.getType());
    result.setInto(fflNode.getInto());
    result.setLimitations(fflNode.getLimitations());
    result.setOrderByFields(fflNode.getOrderBy() != null ? new ArrayList<>(fflNode.getOrderBy()) : new ArrayList<>());

    // SELECT expressions
    if (fflNode.getSelect() != null) {
      for (FullParsSelectField sf : fflNode.getSelect()) {
        String expr = buildSelectExpression(sf);
        result.getSelectExpressions().add(expr);
      }
    }

    // FROM + JOINs + inline subqueries
    if (fflNode.getFrom() != null) {
      for (DataSource ds : fflNode.getFrom()) {
        // Handle inline subquery: from[].subquery references a sub_query node by name
        if (ds.getSubquery() != null) {
          String subqueryName = (String) ds.getSubquery();
          FullParsNode subNode = findSubQueryNode(fflNode, subqueryName);
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
    if (fflNode.getWhere() != null && fflNode.getWhere().getText() != null) {
      result.getWhereConditions().add(fflNode.getWhere().getText());
    }

    // GROUP BY — primary field (array of strings), not group_by_fields
    if (fflNode.getGroupBy() != null) {
      result.getGroupByFields().addAll(fflNode.getGroupBy());
    }

    // HAVING — from HavingBlock
    if (fflNode.getHaving() != null && fflNode.getHaving().getText() != null) {
      result.getHavingConditions().add(fflNode.getHaving().getText());
    }

    // UNION parts — from FFL, not FULL_PARS
    if (fflNode.getUnionNodesIds() != null && !fflNode.getUnionNodesIds().isEmpty()) {
      for (int unionId : fflNode.getUnionNodesIds()) {
        FullParsNode unionNode = fflById.get(unionId);
        if (unionNode != null) {
          RestoredQueryNode unionPart = build(unionNode);
          result.getUnionParts().add(unionPart);
        }
      }
      // Union type from the parent or first part
      if (fflNode.getUnionType() != null) {
        result.setUnionType(fflNode.getUnionType());
      } else if (!result.getUnionParts().isEmpty()) {
        result.setUnionType("union_all");
      }
    }

    // Inline subqueries from subqueryIds (for where, virtualTable, select, joinCondition)
    if (fflNode.getSubqueryIds() != null) {
      for (int subId : fflNode.getSubqueryIds()) {
        FullParsNode subNode = fflById.get(subId);
        if (subNode == null) {
          subNode = fullParsById.get(subId);
        }
        if (subNode == null || subNode.getName() == null) {
          continue;
        }
        String subName = subNode.getName();
        // Skip FROM subqueries — they are already handled above via from[].subquery
        if (result.getInlineSubqueries().containsKey(subName)) {
          continue;
        }
        RestoredQueryNode inlineSub = build(subNode);
        if (subName.contains("_WHERE_") || subName.contains("_HAVING_")) {
          result.getWhereSubqueries().put(subName, inlineSub);
        } else if (subName.contains("_VT_")) {
          result.getVtSubqueries().put(subName, inlineSub);
        } else if (subName.contains("_SELECT_") || subName.contains("_JOIN_")) {
          // SELECT/JOIN subqueries: text already contains parentheses around the name
          // Store in a dedicated map to avoid double parentheses
          result.getWhereSubqueries().put(subName, inlineSub);
        }
      }
    }

    // VT inline subqueries embedded in virtualTable.subqueries (e.g. ВТ_Подзапрос_1)
    extractVtSubqueriesFromDataSources(fflNode.getFrom(), result);

    return result;
  }

  /**
   * Find sub_query node by name among parent's subquery_ids.
   * SRS02 FR-3.4.4: "Сопоставление выполняется через subquery_ids текущей ноды:
   * среди нод из subquery_ids выбирается та, чьё name совпадает со значением from[].subquery"
   */
  private FullParsNode findSubQueryNode(FullParsNode parentNode, String subqueryName) {
    if (parentNode.getSubqueryIds() == null) return null;
    for (int subId : parentNode.getSubqueryIds()) {
      FullParsNode candidate = fflById.get(subId);
      if (candidate != null && subqueryName.equals(candidate.getName())) {
        return candidate;
      }
      // Fallback to full model for inline subqueries not in FFL
      candidate = fullParsById.get(subId);
      if (candidate != null && subqueryName.equals(candidate.getName())) {
        return candidate;
      }
    }
    return null;
  }

  private String buildSelectExpression(FullParsSelectField sf) {
    String text = sf.getText() != null ? sf.getText() : "";
    String alias = sf.getAlias() != null ? sf.getAlias() : "";
    return text + " КАК " + alias;
  }

  /**
   * Extract VT inline subqueries from virtualTable.subqueries (embedded QueryAst).
   * These are NOT referenced via subquery_ids but embedded directly in the DataSource.
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
      // Also check joins
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
