package com.github._1c_syntax.bsl.parser.sdql.md;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class LineageMdBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @SuppressWarnings("unchecked")
  public void build(Path outputDir, String baseName) throws IOException {
    Path lineageDir = outputDir.resolve("lineage_" + baseName);
    Path jsonFile = lineageDir.resolve("field_lineage.json");
    if (!Files.exists(jsonFile)) return;

    Map<String, Object> data = MAPPER.readValue(jsonFile.toFile(), Map.class);
    Object lineage = data.get("field_lineage");

    StringBuilder sb = new StringBuilder();
    sb.append("# Lineage полей: ").append(baseName).append("\n\n");

    if (lineage == null || (lineage instanceof java.util.List && ((java.util.List<?>) lineage).isEmpty())) {
      sb.append("Для данного пакета lineage не обнаружен.\n");
    } else {
      sb.append("```json\n").append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(lineage)).append("\n```\n");
    }

    Files.writeString(lineageDir.resolve("field_lineage.md"), sb.toString());
  }
}
