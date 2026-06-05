package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;

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

    // WHERE — primary field (string), not where_fields
    if (fflNode.getWhere() != null && !fflNode.getWhere().isEmpty()) {
      result.getWhereConditions().add(fflNode.getWhere());
    }

    // GROUP BY — primary field (array of strings), not group_by_fields
    if (fflNode.getGroupBy() != null) {
      result.getGroupByFields().addAll(fflNode.getGroupBy());
    }

    // HAVING — primary field (string), not having_fields
    if (fflNode.getHaving() != null && !fflNode.getHaving().isEmpty()) {
      result.getHavingConditions().add(fflNode.getHaving());
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
        if (subNode != null && subNode.getName() != null && subNode.getName().contains("_INLINE_")) {
          if (!result.getInlineSubqueries().containsKey(subNode.getName())) {
            RestoredQueryNode inlineSub = build(subNode);
            result.getInlineSubqueries().put(subNode.getName(), inlineSub);
          }
        }
      }
    }

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
          sourceTable = src.getVirtualTable();
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
