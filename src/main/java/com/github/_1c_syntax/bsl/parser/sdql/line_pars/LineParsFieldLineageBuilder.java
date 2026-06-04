package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.SelectField;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class LineParsFieldLineageBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path outputDir, String baseName) throws IOException {
    Path lineParsDir = outputDir.getParent().resolve("LINE_PARS");

    LineParsModel model = MAPPER.readValue(
      lineParsDir.resolve("LINE_PARS_model_" + baseName + ".json").toFile(),
      LineParsModel.class);

    List<HierarchyNode> hierarchy = MAPPER.readValue(
      lineParsDir.resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(),
      new TypeReference<List<HierarchyNode>>() {});

    LineParsFieldExtractor extractor = new LineParsFieldExtractor(model, hierarchy);

    List<LineParsNode> targetNodes = selectTargetNodes(model);

    Path fieldLineageDir = outputDir.getParent().resolve("field_lineage");
    Files.createDirectories(fieldLineageDir);

    Path baseDir = fieldLineageDir.resolve(baseName);
    Files.createDirectories(baseDir);

    for (LineParsNode node : targetNodes) {
      if (node.getSelect() == null) {
        continue;
      }
      Path nodeDir = baseDir.resolve(node.getId() + "_" + node.getName());
      Files.createDirectories(nodeDir);

      for (SelectField sf : node.getSelect()) {
        if (sf.getAlias() == null) {
          continue;
        }
        FieldLineageNode lineage = extractor.extract(node.getId(), sf.getAlias());
        if (lineage == null) {
          continue;
        }
        String fileName = "FLS_" + baseName + "_" + node.getId() + "_" + node.getName()
          + "_" + sf.getAlias() + ".json";
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
          nodeDir.resolve(fileName).toFile(), lineage);
      }
    }
  }

  private List<LineParsNode> selectTargetNodes(LineParsModel model) {
    List<LineParsNode> resultNodes = model.getNodes().stream()
      .filter(n -> "result".equals(n.getType()))
      .collect(Collectors.toList());

    if (!resultNodes.isEmpty()) {
      return resultNodes;
    }

    return model.getNodes().stream()
      .filter(n -> "temp_query".equals(n.getType()))
      .max(Comparator.comparingInt(LineParsNode::getId))
      .map(List::of)
      .orElse(List.of());
  }
}
