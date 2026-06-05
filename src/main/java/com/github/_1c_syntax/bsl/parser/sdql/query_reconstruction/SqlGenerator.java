package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
   * No INTO, no trailing semicolon. Preserves multi-line format with indentation.
   */
  public String generateInline(RestoredQueryNode node) {
    String sql = generate(node);
    // Remove trailing semicolon for inline use
    if (sql.endsWith(";")) {
      sql = sql.substring(0, sql.length() - 1);
    }
    // For sub_query nodes, indent each line for inline use inside parent FROM
    if (isSubQuery(node)) {
      String[] lines = sql.split("\n");
      StringBuilder sb = new StringBuilder();
      for (String line : lines) {
        sb.append("    ").append(line).append("\n");
      }
      sql = sb.toString().trim();
    }
    return sql;
  }

  /**
   * Generate compact single-line SQL for inline use in WHERE/VT conditions.
   * No INTO, no trailing semicolon, no newlines.
   */
  public String generateCompactInline(RestoredQueryNode node) {
    String sql = generateInline(node);
    // Collapse to single line
    sql = sql.replace("\n", " ");
    sql = sql.replaceAll("\\s+", " ");
    return sql.trim();
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

    // INTO — only for temp_query, not for sub_query or union parts
    if (node.getInto() != null && isTempQuery(node) && !isSubQuery(node)) {
      sb.append("ПОМЕСТИТЬ ").append(node.getInto()).append("\n");
    }

    // FROM + JOINs (JOINs are output after their associated source, including nested)
    if (!node.getFrom().isEmpty()) {
      sb.append("ИЗ\n");
      for (int i = 0; i < node.getFrom().size(); i++) {
        DataSource ds = node.getFrom().get(i);
        sb.append("    ").append(formatDataSource(ds, node));
        // Output JOINs associated with this source (including nested)
        if (ds.getJoins() != null) {
          for (JoinPart jp : ds.getJoins()) {
            sb.append(formatJoinRecursive(jp, node, 2));
          }
        }
        if (i < node.getFrom().size() - 1) {
          sb.append(",");
        }
        sb.append("\n");
      }
    }

    // Note: RestoredQueryNode.joins is used by QueryReconstructor (BRD02),
    // but for extraction we use DataSource.joins directly above.

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
        source = "(" + subSql + ")";
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

    String alias;
    if (ds.getAlias() != null) {
      alias = ds.getAlias();
    } else if (source.contains(".")) {
      // For tables like "Справочник.Контрагенты", use "Контрагенты" as alias
      alias = source.substring(source.lastIndexOf('.') + 1);
    } else {
      alias = source;
    }
    return source + " КАК " + alias;
  }

  /**
   * Recursively format a JOIN and its nested JOINs with proper indentation.
   * @param indentLevel indentation level (2 = 8 spaces for first-level JOIN)
   */
  private String formatJoinRecursive(JoinPart jp, RestoredQueryNode parentNode, int indentLevel) {
    StringBuilder sb = new StringBuilder();
    String indent = "    ".repeat(indentLevel);
    sb.append("\n");
    sb.append(indent).append(formatJoinType(jp.getJoinType())).append(" ");
    sb.append(formatJoinSource(jp.getSource(), parentNode)).append(" КАК ").append(jp.getSource().getAlias()).append("\n");
    sb.append(indent).append("ПО ").append(inlineSubqueries(jp.getCondition(), parentNode));
    // Recursively output nested JOINs
    if (jp.getSource() != null && jp.getSource().getJoins() != null) {
      for (JoinPart nestedJp : jp.getSource().getJoins()) {
        sb.append(formatJoinRecursive(nestedJp, parentNode, indentLevel + 1));
      }
    }
    return sb.toString();
  }

  private String formatJoinSource(DataSource src, RestoredQueryNode parentNode) {
    if (src.getTable() != null) {
      return src.getTable();
    } else if (src.getVirtualTable() != null) {
      return inlineSubqueries(src.getVirtualTable().getText(), parentNode);
    } else if (src.getSubquery() != null) {
      String subqueryName = (String) src.getSubquery();
      RestoredQueryNode inlineSub = parentNode.getInlineSubqueries().get(subqueryName);
      if (inlineSub != null) {
        String subSql = generateInline(inlineSub);
        return "(" + subSql + ")";
      }
      return subqueryName;
    } else if (src.getExternalDataSource() != null) {
      return src.getExternalDataSource();
    } else if (src.getParameterTable() != null) {
      return src.getParameterTable();
    }
    return "?";
  }

  /**
   * Replace inline subquery names with their SQL in the given text.
   * Uses compact format for WHERE/VT subqueries, multi-line for FROM subqueries.
   */
  private String inlineSubqueries(String text, RestoredQueryNode node) {
    if (text == null) {
      return text;
    }

    String result = text;

    // FROM subqueries — multi-line with indentation
    for (Map.Entry<String, RestoredQueryNode> entry : node.getInlineSubqueries().entrySet()) {
      String name = entry.getKey();
      if (result.contains(name)) {
        String subSql = generateInline(entry.getValue());
        result = result.replace(name, "(" + subSql + ")");
      }
    }

    // WHERE/HAVING subqueries — compact single-line
    // Note: text already contains parentheses around the subquery name, e.g. (Результат_1_WHERE_1)
    for (Map.Entry<String, RestoredQueryNode> entry : node.getWhereSubqueries().entrySet()) {
      String name = entry.getKey();
      if (result.contains(name)) {
        String subSql = generateCompactInline(entry.getValue());
        result = result.replace(name, subSql);
      }
    }

    // VT subqueries — compact single-line
    // Note: text already contains parentheses around the subquery name, e.g. (Результат_1_VT_1)
    for (Map.Entry<String, RestoredQueryNode> entry : node.getVtSubqueries().entrySet()) {
      String name = entry.getKey();
      if (result.contains(name)) {
        String subSql = generateCompactInline(entry.getValue());
        result = result.replace(name, subSql);
      }
    }

    return result;
  }

  private String formatJoinType(String joinType) {
    if (joinType == null) return "ЛЕВОЕ СОЕДИНЕНИЕ";
    return switch (joinType) {
      case "right" -> "ПРАВОЕ СОЕДИНЕНИЕ";
      case "full" -> "ПОЛНОЕ СОЕДИНИНИЕ";
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
