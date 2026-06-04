package com.github._1c_syntax.bsl.parser.sdql.md;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class LineParsModelMdBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path lineParsDir, String baseName) throws IOException {
    Path jsonFile = lineParsDir.resolve("LINE_PARS_model_" + baseName + ".json");
    if (!Files.exists(jsonFile)) return;

    LineParsModel model = MAPPER.readValue(jsonFile.toFile(), LineParsModel.class);
    StringBuilder sb = new StringBuilder();

    sb.append("# LINE_PARS модель: ").append(baseName).append("\n\n");

    // Узлы
    sb.append("## Узлы\n\n");
    sb.append("| ID | SDBL ID | Имя | Тип | Родитель | Подзапросы | UNION части |\n");
    sb.append("|---|---|---|---|---|---|---|\n");

    if (model.getNodes() != null) {
      for (LineParsNode node : model.getNodes()) {
        String parent = node.getUpqueryId() != null ? String.valueOf(node.getUpqueryId()) : "—";
        String subs = node.getSubqueryIds() != null && !node.getSubqueryIds().isEmpty()
          ? node.getSubqueryIds().stream().map(String::valueOf).collect(Collectors.joining(", "))
          : "—";
        String unions = node.getUnionNodesIds() != null && !node.getUnionNodesIds().isEmpty()
          ? node.getUnionNodesIds().stream().map(String::valueOf).collect(Collectors.joining(", "))
          : "—";

        sb.append("| ").append(node.getId())
          .append(" | ").append(node.getSdblId())
          .append(" | ").append(escapeMd(nullSafe(node.getName())))
          .append(" | ").append(escapeMd(nullSafe(node.getType())))
          .append(" | ").append(parent)
          .append(" | ").append(subs)
          .append(" | ").append(unions)
          .append(" |\n");
      }
    }
    sb.append("\n");

    // Уничтожаемые таблицы
    if (model.getDropQueries() != null && !model.getDropQueries().isEmpty()) {
      sb.append("## Уничтожаемые таблицы\n\n");
      for (String dq : model.getDropQueries()) {
        sb.append("- ").append(escapeMd(dq)).append("\n");
      }
      sb.append("\n");
    }

    // Edges (зарезервировано)
    if (model.getEdges() != null && !model.getEdges().isEmpty()) {
      sb.append("## Рёбра\n\n");
      sb.append("| От | От (имя) | К | К (имя) |\n");
      sb.append("|---|---|---|---|\n");
      for (var edge : model.getEdges()) {
        String fromName = findNodeName(model.getNodes(), edge.getFrom());
        String toName = findNodeName(model.getNodes(), edge.getTo());
        sb.append("| ").append(edge.getFrom())
          .append(" | ").append(escapeMd(nullSafe(fromName)))
          .append(" | ").append(edge.getTo())
          .append(" | ").append(escapeMd(nullSafe(toName)))
          .append(" |\n");
      }
      sb.append("\n");
    }

    Files.writeString(lineParsDir.resolve("LINE_PARS_model_" + baseName + ".md"), sb.toString());
  }

  private String findNodeName(List<LineParsNode> nodes, int id) {
    if (nodes == null) return null;
    return nodes.stream()
      .filter(n -> n.getId() == id)
      .findFirst()
      .map(LineParsNode::getName)
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
