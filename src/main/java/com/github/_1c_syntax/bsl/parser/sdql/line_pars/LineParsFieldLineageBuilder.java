package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.SelectField;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LineParsFieldLineageBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path lineParsDir, String baseName) throws IOException {
    LineParsModel model = MAPPER.readValue(
      lineParsDir.resolve("LINE_PARS_model_" + baseName + ".json").toFile(),
      LineParsModel.class);

    List<HierarchyNode> hierarchy = MAPPER.readValue(
      lineParsDir.resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(),
      new TypeReference<List<HierarchyNode>>() {});

    LineParsFieldExtractor extractor = new LineParsFieldExtractor(model, hierarchy);
    List<FieldLineageNode> result = new ArrayList<>();

    for (LineParsNode node : model.getNodes()) {
      if (node.getSelect() == null) {
        continue;
      }
      for (SelectField sf : node.getSelect()) {
        if (sf.getAlias() == null) {
          continue;
        }
        FieldLineageNode lineage = extractor.extract(node.getId(), sf.getAlias());
        if (lineage != null) {
          result.add(lineage);
        }
      }
    }

    MAPPER.writerWithDefaultPrettyPrinter().writeValue(
      lineParsDir.resolve("LINE_PARS_field_lineage_" + baseName + ".json").toFile(),
      result);
  }
}
