package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;

import java.util.ArrayList;
import java.util.List;

public class SqlGenerator {

  public String generate(RestoredQueryNode node) {
    if (!node.getUnionParts().isEmpty()) {
      return generateUnion(node);
    }
    return generateSingleQuery(node);
  }

  private String generateUnion(RestoredQueryNode node) {
    List<String> parts = new ArrayList<>();
    for (RestoredQueryNode unionPart : node.getUnionParts()) {
      parts.add(generateSingleQuery(unionPart));
    }

    String separator = "union_all".equals(node.getUnionType())
      ? "\n\nОБЪЕДИНИТЬ ВСЕ\n\n"
      : "\n\nОБЪЕДИНИТЬ\n\n";

    String query = String.join(separator, parts);

    if (node.getInto() != null) {
      query += "\n\nПОМЕСТИТЬ " + node.getInto();
    }

    return query;
  }

  private String generateSingleQuery(RestoredQueryNode node) {
    StringBuilder sb = new StringBuilder();

    // SELECT
    sb.append("ВЫБРАТЬ");
    if (node.getLimitations() != null) {
      sb.append(" ").append(node.getLimitations());
    }
    sb.append("\n");

    List<String> selectLines = node.getSelectExpressions();
    for (int i = 0; i < selectLines.size(); i++) {
      sb.append("    ").append(selectLines.get(i));
      if (i < selectLines.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
    }

    // INTO
    if (node.getInto() != null && isTempQuery(node)) {
      sb.append("ПОМЕСТИТЬ ").append(node.getInto()).append("\n");
    }

    // FROM
    if (!node.getFrom().isEmpty()) {
      sb.append("ИЗ\n");
      List<String> fromLines = new ArrayList<>();
      for (DataSource ds : node.getFrom()) {
        fromLines.add(formatDataSource(ds));
      }
      for (int i = 0; i < fromLines.size(); i++) {
        sb.append("    ").append(fromLines.get(i));
        if (i < fromLines.size() - 1) {
          sb.append(",");
        }
        sb.append("\n");
      }
    }

    // JOINs
    for (RestoredJoin join : node.getJoins()) {
      sb.append(formatJoinType(join.getJoinType())).append(" ");
      sb.append(join.getSourceTable()).append(" КАК ").append(join.getAlias()).append("\n");
      sb.append("ПО ").append(join.getCondition()).append("\n");
    }

    // WHERE
    if (!node.getWhereConditions().isEmpty()) {
      sb.append("ГДЕ\n    ");
      sb.append(String.join("\n    И ", node.getWhereConditions()));
      sb.append("\n");
    }

    // GROUP BY
    if (!node.getGroupByFields().isEmpty()) {
      sb.append("СГРУППИРОВАТЬ ПО\n    ");
      sb.append(String.join(",\n    ", node.getGroupByFields()));
      sb.append("\n");
    }

    // HAVING
    if (!node.getHavingConditions().isEmpty()) {
      sb.append("ИМЕЮЩИЕ\n    ");
      sb.append(String.join("\n    И ", node.getHavingConditions()));
      sb.append("\n");
    }

    // ORDER BY
    if (!node.getOrderByFields().isEmpty()) {
      sb.append("УПОРЯДОЧИТЬ ПО\n    ");
      sb.append(String.join(",\n    ", node.getOrderByFields()));
      sb.append("\n");
    }

    return sb.toString().trim();
  }

  private String formatDataSource(DataSource ds) {
    String source;
    if (ds.getTable() != null) {
      source = ds.getTable();
    } else if (ds.getVirtualTable() != null) {
      source = ds.getVirtualTable();
    } else if (ds.getSubquery() != null) {
      source = (String) ds.getSubquery();
    } else if (ds.getExternalDataSource() != null) {
      source = ds.getExternalDataSource();
    } else if (ds.getParameterTable() != null) {
      source = ds.getParameterTable();
    } else {
      source = "?";
    }

    String alias = ds.getAlias() != null ? ds.getAlias() : source;
    return source + " КАК " + alias;
  }

  private String formatJoinType(String joinType) {
    if (joinType == null) return "ЛЕВОЕ СОЕДИНЕНИЕ";
    return switch (joinType) {
      case "right" -> "ПРАВОЕ СОЕДИНЕНИЕ";
      case "full" -> "ПОЛНОЕ СОЕДИНЕНИЕ";
      case "inner" -> "ВНУТРЕННЕЕ СОЕДИНЕНИЕ";
      default -> "ЛЕВОЕ СОЕДИНЕНИЕ";
    };
  }

  private boolean isTempQuery(RestoredQueryNode node) {
    return "temp_query".equals(node.getType()) || node.getInto() != null;
  }
}
