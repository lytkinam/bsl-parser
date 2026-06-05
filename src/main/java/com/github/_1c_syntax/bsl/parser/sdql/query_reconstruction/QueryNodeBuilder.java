package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsChildField;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsConditionField;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsJoinCondition;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QueryNodeBuilder {

  private final Map<Integer, FullParsNode> fullParsById;

  public QueryNodeBuilder(Map<Integer, FullParsNode> fullParsById) {
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

    // FROM + JOINs
    if (fflNode.getFrom() != null) {
      for (DataSource ds : fflNode.getFrom()) {
        result.getFrom().add(ds);
        extractJoins(ds, result);
      }
    }

    // WHERE
    for (FullParsConditionField cf : fflNode.getWhereFields()) {
      result.getWhereConditions().add(cf.getText());
    }

    // GROUP BY
    for (FullParsConditionField cf : fflNode.getGroupByFields()) {
      result.getGroupByFields().add(cf.getText());
    }

    // HAVING
    for (FullParsConditionField cf : fflNode.getHavingFields()) {
      result.getHavingConditions().add(cf.getText());
    }

    // UNION parts
    if (fflNode.getUnionNodesIds() != null && !fflNode.getUnionNodesIds().isEmpty()) {
      for (int unionId : fflNode.getUnionNodesIds()) {
        FullParsNode unionNode = fullParsById.get(unionId);
        if (unionNode != null) {
          RestoredQueryNode unionPart = build(unionNode);
          result.getUnionParts().add(unionPart);
        }
      }
      // Union type from the first part or default
      if (!result.getUnionParts().isEmpty()) {
        FullParsNode firstUnion = fullParsById.get(fflNode.getUnionNodesIds().get(0));
        if (firstUnion != null && firstUnion.getUnionType() != null) {
          result.setUnionType(firstUnion.getUnionType());
        } else {
          result.setUnionType("union_all");
        }
      }
    }

    return result;
  }

  private String buildSelectExpression(FullParsSelectField sf) {
    String text = sf.getText() != null ? sf.getText() : "";
    String alias = sf.getAlias() != null ? sf.getAlias() : "";

    // Always output "text КАК alias" per SRS02 FR-3.3.1
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
