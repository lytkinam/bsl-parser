package com.github._1c_syntax.bsl.parser.sdql.md;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.FieldLineageNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class LineParsFieldLineageMdBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path fieldLineageDir, String baseName) throws IOException {
    Path baseDir = fieldLineageDir.resolve(baseName);
    if (!Files.exists(baseDir)) {
      return;
    }

    try (Stream<Path> nodeDirs = Files.list(baseDir).filter(Files::isDirectory)) {
      for (Path nodeDir : nodeDirs.toList()) {
        try (Stream<Path> jsonFiles = Files.list(nodeDir)
            .filter(p -> p.toString().endsWith(".json"))) {
          for (Path jsonFile : jsonFiles.toList()) {
            FieldLineageNode lineage = MAPPER.readValue(jsonFile.toFile(), FieldLineageNode.class);
            String mdContent = renderMarkdown(lineage);
            Path mdFile = jsonFile.resolveSibling(
                jsonFile.getFileName().toString().replace(".json", ".md"));
            Files.writeString(mdFile, mdContent);
          }
        }
      }
    }
  }

  private String renderMarkdown(FieldLineageNode node) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Lineage поля: ").append(escapeMd(nullSafe(node.getAlias()))).append("\n\n");

    sb.append("## Корневой узел\n\n");
    sb.append("- **Узел:** ").append(escapeMd(nullSafe(node.getName()))).append("\n");
    if (node.getId() != null) {
      sb.append("- **ID:** ").append(node.getId()).append("\n");
    }
    sb.append("- **Выражение:** `").append(escapeMd(nullSafe(node.getText()))).append("`\n\n");

    if (node.getChildFields() == null || node.getChildFields().isEmpty()) {
      sb.append("_Нет дочерних полей_\n");
    } else {
      sb.append("## Дерево lineage\n\n");
      sb.append("```\n");
      appendTreeNode(sb, node, "", true);
      sb.append("```\n");
    }

    return sb.toString();
  }

  private void appendTreeNode(StringBuilder sb, FieldLineageNode node, String prefix,
                              boolean isLast) {
    String connector = isLast ? "└── " : "├── ";
    String linePrefix = prefix + connector;
    String continuationPrefix = prefix + (isLast ? "    " : "│   ");

    String label = buildLabel(node);
    // Insert linePrefix before first line, continuationPrefix before continuation lines
    String indentedLabel = indentMultiline(label, linePrefix, continuationPrefix);

    sb.append(indentedLabel).append("\n");

    if (node.getChildFields() != null && !node.getChildFields().isEmpty()) {
      String childPrefix = prefix + (isLast ? "    " : "│   ");
      for (int i = 0; i < node.getChildFields().size(); i++) {
        appendTreeNode(sb, node.getChildFields().get(i), childPrefix,
            i == node.getChildFields().size() - 1);
      }
    }
  }

  private String buildLabel(FieldLineageNode node) {
    StringBuilder label = new StringBuilder();
    label.append(escapeMd(nullSafe(node.getAlias())));
    if (node.getName() != null && !node.getName().equals(node.getAlias())) {
      label.append(" (").append(escapeMd(node.getName())).append(")");
    }
    if (node.getText() != null) {
      label.append(": ").append(escapeMd(node.getText()));
    }
    if (node.getSource() != null) {
      label.append(" → `").append(escapeMd(node.getSource())).append("`");
    }
    return label.toString();
  }

  private String indentMultiline(String text, String firstPrefix, String continuationPrefix) {
    String[] lines = text.split("\n", -1);
    if (lines.length <= 1) {
      return firstPrefix + text;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        sb.append("\n");
      }
      String prefix = (i == 0) ? firstPrefix : continuationPrefix;
      sb.append(prefix).append(lines[i]);
    }
    return sb.toString();
  }

  private String nullSafe(String s) {
    return s != null ? s : "";
  }

  private String escapeMd(String s) {
    if (s == null) return "";
    return s.replace("|", "\\|").replace("\r", "").replace("\t", "    ");
  }
}
