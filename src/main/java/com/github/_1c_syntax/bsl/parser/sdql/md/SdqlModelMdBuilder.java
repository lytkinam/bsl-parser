package com.github._1c_syntax.bsl.parser.sdql.md;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SdqlModelMdBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path outputDir, String baseName) throws IOException {
    Path modelFile = outputDir.resolve("sdbl_parse_model_" + baseName + ".json");
    if (!Files.exists(modelFile)) return;

    QueryModel model = MAPPER.readValue(modelFile.toFile(), QueryModel.class);
    StringBuilder sb = new StringBuilder();

    sb.append("# Пакет запросов: ").append(baseName).append("\n\n");

    // Общая информация
    sb.append("## Общая информация\n\n");
    sb.append("- Узлов: ").append(model.getNodes() != null ? model.getNodes().size() : 0).append("\n");
    sb.append("- Рёбер: ").append(model.getEdges() != null ? model.getEdges().size() : 0).append("\n");
    sb.append("- Хеш источника: `").append(model.getSourceHash()).append("`\n");
    sb.append("- Длина источника: ").append(model.getSourceLength()).append(" симв.\n\n");

    // Таблица узлов
    sb.append("## Запросы\n\n");
    sb.append("| ID | Тип | Имя | Длина, симв. |\n");
    sb.append("|---|---|---|---|\n");
    if (model.getNodes() != null) {
      for (QueryNode node : model.getNodes()) {
        sb.append("| ").append(node.getId())
          .append(" | ").append(nullSafe(node.getType()))
          .append(" | ").append(nullSafe(node.getName()))
          .append(" | ").append(node.getTextLength())
          .append(" |\n");
      }
    }
    sb.append("\n");

    // Таблица рёбер
    if (model.getEdges() != null && !model.getEdges().isEmpty()) {
      sb.append("## Рёбра (зависимости ВТ)\n\n");
      sb.append("| От (ID) | От (имя) | К (ID) | К (имя) |\n");
      sb.append("|---|---|---|---|\n");
      for (QueryEdge edge : model.getEdges()) {
        String fromName = findNodeName(model.getNodes(), edge.getFrom());
        String toName = findNodeName(model.getNodes(), edge.getTo());
        sb.append("| ").append(edge.getFrom())
          .append(" | ").append(nullSafe(fromName))
          .append(" | ").append(edge.getTo())
          .append(" | ").append(nullSafe(toName))
          .append(" |\n");
      }
      sb.append("\n");
    }

    // Детали AST
    if (model.getNodes() != null) {
      for (QueryNode node : model.getNodes()) {
        appendNodeDetails(sb, node);
      }
    }

    Path mdFile = outputDir.resolve("sdbl_parse_model_" + baseName + ".md");
    Files.writeString(mdFile, sb.toString());
  }

  private void appendNodeDetails(StringBuilder sb, QueryNode node) {
    String name = node.getName() != null ? node.getName() : "Результат_" + node.getId();
    sb.append("---\n\n");
    sb.append("## AST узла ").append(node.getId()).append(": ")
      .append(name).append(" (").append(nullSafe(node.getType())).append(")\n\n");

    QueryAst ast = node.getQuery();
    if (ast == null) {
      sb.append("_AST отсутствует_\n\n");
      return;
    }

    // SELECT
    if (ast.getSelect() != null && !ast.getSelect().isEmpty()) {
      sb.append("### SELECT\n\n");
      sb.append("| Выражение | Псевдоним |\n");
      sb.append("|---|---|\n");
      for (SelectField sf : ast.getSelect()) {
        sb.append("| ").append(escapeMd(nullSafe(sf.getText())))
          .append(" | ").append(escapeMd(nullSafe(sf.getAlias())))
          .append(" |\n");
      }
      sb.append("\n");
    }

    // INTO
    if (ast.getInto() != null) {
      sb.append("### ПОМЕСТИТЬ\n\n`").append(escapeMd(ast.getInto())).append("`\n\n");
    }

    // FROM
    if (ast.getFrom() != null && !ast.getFrom().isEmpty()) {
      sb.append("### FROM\n\n");
      sb.append("| Таблица | Алиас | Тип соединения | Условие |\n");
      sb.append("|---|---|---|---|\n");
      for (DataSource ds : ast.getFrom()) {
        appendDataSourceRow(sb, ds, "—");
      }
      sb.append("\n");
    }

    // WHERE
    if (ast.getWhere() != null && ast.getWhere().getText() != null) {
      sb.append("### WHERE\n\n```sql\n").append(ast.getWhere().getText()).append("\n```\n\n");
    }

    // GROUP BY
    if (ast.getGroupBy() != null && !ast.getGroupBy().isEmpty()) {
      sb.append("### GROUP BY\n\n");
      for (String gb : ast.getGroupBy()) {
        sb.append("- `").append(escapeMd(gb)).append("`\n");
      }
      sb.append("\n");
    }

    // HAVING
    if (ast.getHaving() != null && ast.getHaving().getText() != null) {
      sb.append("### HAVING\n\n```sql\n").append(ast.getHaving().getText()).append("\n```\n\n");
    }

    // ORDER BY
    if (ast.getOrderBy() != null && !ast.getOrderBy().isEmpty()) {
      sb.append("### ORDER BY\n\n");
      for (String ob : ast.getOrderBy()) {
        sb.append("- `").append(escapeMd(ob)).append("`\n");
      }
      sb.append("\n");
    }

    // UNION
    if (ast.getUnions() != null && !ast.getUnions().isEmpty()) {
      sb.append("### UNION\n\n");
      for (UnionPart up : ast.getUnions()) {
        sb.append("- **").append(nullSafe(up.getUnionType())).append("**\n");
      }
      sb.append("\n");
    }
  }

  private void appendDataSourceRow(StringBuilder sb, DataSource ds, String joinType) {
    String table = ds.getTable() != null ? ds.getTable()
      : ds.getVirtualTable() != null ? ds.getVirtualTable().getText()
      : ds.getParameterTable() != null ? ds.getParameterTable()
      : ds.getExternalDataSource() != null ? ds.getExternalDataSource()
      : ds.getSubquery() != null ? "(подзапрос)"
      : "?";
    sb.append("| ").append(escapeMd(table))
      .append(" | ").append(escapeMd(nullSafe(ds.getAlias())))
      .append(" | ").append(escapeMd(joinType))
      .append(" | ").append(" |\n");

    if (ds.getJoins() != null) {
      for (JoinPart jp : ds.getJoins()) {
        String jt = jp.getJoinType() != null ? jp.getJoinType().toUpperCase() + " СОЕДИНЕНИЕ" : "СОЕДИНЕНИЕ";
        appendDataSourceRow(sb, jp.getSource(), jt);
      }
    }
  }

  private String findNodeName(List<QueryNode> nodes, int id) {
    if (nodes == null) return null;
    return nodes.stream()
      .filter(n -> n.getId() == id)
      .findFirst()
      .map(QueryNode::getName)
      .orElse(null);
  }

  private String nullSafe(String s) {
    return s != null ? s : "";
  }

  private String escapeMd(String s) {
    if (s == null) return "";
    return s.replace("|", "\\|").replace("\n", "<br>").replace("\r", "");
  }
}
