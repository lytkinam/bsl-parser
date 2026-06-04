package com.github._1c_syntax.bsl.parser.sdql.md;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.HierarchyNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class HierarchyMdBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path lineParsDir, String baseName) throws IOException {
    Path jsonFile = lineParsDir.resolve("LINE_PARS_hierarchy_" + baseName + ".json");
    if (!Files.exists(jsonFile)) return;

    List<HierarchyNode> nodes = MAPPER.readValue(jsonFile.toFile(), new TypeReference<List<HierarchyNode>>() {});
    StringBuilder sb = new StringBuilder();

    sb.append("# Иерархия таблиц: ").append(baseName).append("\n\n");

    for (HierarchyNode node : nodes) {
      String idPart = node.getId() != null ? " (ID: " + node.getId() + ")" : "";
      sb.append("## ").append(escapeMd(nullSafe(node.getName()))).append(idPart).append("\n");

      if (node.getTableHierarchy() == null || node.getTableHierarchy().isEmpty()) {
        sb.append("_Нет источников_\n");
      } else {
        for (HierarchyNode child : node.getTableHierarchy()) {
          appendHierarchyNode(sb, child, 0);
        }
      }
      sb.append("\n");
    }

    Files.writeString(lineParsDir.resolve("LINE_PARS_hierarchy_" + baseName + ".md"), sb.toString());
  }

  private void appendHierarchyNode(StringBuilder sb, HierarchyNode node, int depth) {
    String indent = "  ".repeat(depth);
    String icon = "📄";
    if (node.getId() != null) {
      icon = "📁";
    }
    String idPart = node.getId() != null ? " (ID: " + node.getId() + ")" : "";
    sb.append(indent).append("- ").append(icon).append(" ")
      .append(escapeMd(nullSafe(node.getName()))).append(idPart);
    if (node.getSource() != null) {
      sb.append(" → `").append(escapeMd(node.getSource())).append("`");
    }
    if (node.getTypeHierarchy() != null) {
      sb.append(" — *").append(escapeMd(node.getTypeHierarchy())).append("*");
    }
    sb.append("\n");

    if (node.getTableHierarchy() != null) {
      for (HierarchyNode child : node.getTableHierarchy()) {
        appendHierarchyNode(sb, child, depth + 1);
      }
    }
  }

  private String nullSafe(String s) {
    return s != null ? s : "";
  }

  private String escapeMd(String s) {
    if (s == null) return "";
    return s.replace("|", "\\|").replace("\n", "<br>").replace("\r", "");
  }
}
