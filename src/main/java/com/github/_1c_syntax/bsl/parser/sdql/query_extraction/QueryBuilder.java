package com.github._1c_syntax.bsl.parser.sdql.query_extraction;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;
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

    // WHERE — primary field (string)
    if (fpNode.getWhere() != null && !fpNode.getWhere().isEmpty()) {
      result.getWhereConditions().add(fpNode.getWhere());
    }

    // GROUP BY — primary field (array of strings)
    if (fpNode.getGroupBy() != null) {
      result.getGroupByFields().addAll(fpNode.getGroupBy());
    }

    // HAVING — primary field (string)
    if (fpNode.getHaving() != null && !fpNode.getHaving().isEmpty()) {
      result.getHavingConditions().add(fpNode.getHaving());
    }

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
