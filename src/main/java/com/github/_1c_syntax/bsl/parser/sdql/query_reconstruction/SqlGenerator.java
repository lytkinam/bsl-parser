package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SqlGenerator {

  /**
   * Generate SQL for a node. For sub_query nodes, generates inline SQL without INTO and semicolon.
   */
  public String generate(RestoredQueryNode node) {
    if (!node.getUnionParts().isEmpty()) {
      return generateUnion(node);
    }
    return generateSingleQuery(node);
  }

  /**
   * Generate SQL for inline use (inside parent FROM clause).
   * No INTO, no trailing semicolon.
   */
  public String generateInline(RestoredQueryNode node) {
    String sql = generate(node);
    // Remove trailing semicolon for inline use
    if (sql.endsWith(";")) {
      sql = sql.substring(0, sql.length() - 1);
    }
    return sql;
  }

  private String generateUnion(RestoredQueryNode node) {
    List<String> parts = new ArrayList<>();
    for (RestoredQueryNode unionPart : node.getUnionParts()) {
      parts.add(generateSingleQuery(unionPart));
    }

    String separator = "union_all".equals(node.getUnionType())
      ? "\n\nОБЪЕДИНИТЬ ВСЕ\n\n"
      : "\n\nОБЪЕДИНИТЬ\n\n";

    // If temp_query with into — insert ПОМЕСТИТЬ into UNION_0 between SELECT and FROM
    if (node.getInto() != null && !parts.isEmpty() && isTempQuery(node)) {
      String firstPart = parts.get(0);
      firstPart = insertBeforeFirstFrom(firstPart, "\nПОМЕСТИТЬ " + node.getInto());
      parts.set(0, firstPart);
    }

    return String.join(separator, parts);
  }

  /**
   * Insert text before the first occurrence of "ИЗ" or "FROM" (case-insensitive for SDBL).
   * Looks for "\nИЗ" to avoid matching "ИЗ" inside identifiers.
   */
  private String insertBeforeFirstFrom(String query, String toInsert) {
    int idx = query.indexOf("\nИЗ");
    if (idx == -1) {
      idx = query.indexOf("\nИЗ ");
    }
    if (idx == -1) {
      // Fallback: try without newline
      idx = query.indexOf("ИЗ");
    }
    if (idx == -1) {
      // No FROM found — append at end
      return query + toInsert;
    }
    return query.substring(0, idx) + toInsert + query.substring(idx);
  }

  private String generateSingleQuery(RestoredQueryNode node) {
    StringBuilder sb = new StringBuilder();

    // SELECT — indent for subqueries
    if (isSubQuery(node)) {
      sb.append("    ");
    }
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

    // INTO — only for temp_query, not for sub_query or union parts
    if (node.getInto() != null && isTempQuery(node) && !isSubQuery(node)) {
      sb.append("ПОМЕСТИТЬ ").append(node.getInto()).append("\n");
    }

    // FROM
    if (!node.getFrom().isEmpty()) {
      sb.append("ИЗ\n");
      List<String> fromLines = new ArrayList<>();
      for (DataSource ds : node.getFrom()) {
        fromLines.add(formatDataSource(ds, node));
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
      sb.append(inlineSubqueries(join.getSourceTable(), node)).append(" КАК ").append(join.getAlias()).append("\n");
      sb.append("ПО ").append(inlineSubqueries(join.getCondition(), node)).append("\n");
    }

    // WHERE
    if (!node.getWhereConditions().isEmpty()) {
      sb.append("ГДЕ\n    ");
      List<String> whereLines = new ArrayList<>();
      for (String cond : node.getWhereConditions()) {
        whereLines.add(inlineSubqueries(cond, node));
      }
      sb.append(String.join("\n    И ", whereLines));
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
      List<String> havingLines = new ArrayList<>();
      for (String cond : node.getHavingConditions()) {
        havingLines.add(inlineSubqueries(cond, node));
      }
      sb.append(String.join("\n    И ", havingLines));
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

  private String formatDataSource(DataSource ds, RestoredQueryNode parentNode) {
    String source;
    if (ds.getTable() != null) {
      source = ds.getTable();
    } else if (ds.getVirtualTable() != null) {
      source = inlineSubqueries(ds.getVirtualTable().getText(), parentNode);
    } else if (ds.getSubquery() != null) {
      // Inline subquery: generate SQL from the inline subquery node
      String subqueryName = (String) ds.getSubquery();
      RestoredQueryNode inlineSub = parentNode.getInlineSubqueries().get(subqueryName);
      if (inlineSub != null) {
        String subSql = generateInline(inlineSub);
        source = "(\n" + indent(subSql) + "\n    )";
      } else {
        source = subqueryName;
      }
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

  /**
   * Replace inline subquery names with their SQL in the given text.
   */
  private String inlineSubqueries(String text, RestoredQueryNode node) {
    if (text == null || node.getInlineSubqueries().isEmpty()) {
      return text;
    }
    String result = text;
    for (Map.Entry<String, RestoredQueryNode> entry : node.getInlineSubqueries().entrySet()) {
      String name = entry.getKey();
      if (result.contains(name)) {
        String subSql = generateInline(entry.getValue());
        result = result.replace(name, "(\n" + indent(subSql) + "\n    )");
      }
    }
    return result;
  }

  private String indent(String sql) {
    String[] lines = sql.split("\n");
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      sb.append("        ").append(line).append("\n");
    }
    return sb.toString().trim();
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

  private boolean isSubQuery(RestoredQueryNode node) {
    return "sub_query".equals(node.getType());
  }
}
