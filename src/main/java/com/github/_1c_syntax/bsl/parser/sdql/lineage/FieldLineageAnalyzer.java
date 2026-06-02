package com.github._1c_syntax.bsl.parser.sdql.lineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github._1c_syntax.bsl.parser.sdql.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class FieldLineageAnalyzer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void analyze(QueryModel model, Path outputDir) throws IOException {
    Path lineageDir = outputDir.resolve("lineage");
    Files.createDirectories(lineageDir);

    // Find all fields named "ВидОбязательств" or similar
    Map<String, List<FieldRecord>> fieldsNode = MAPPER.readValue(
      outputDir.resolve("fields_node/fields_node.json").toFile(),
      MAPPER.getTypeFactory().constructMapType(Map.class, String.class, List.class)
    );

    ObjectNode lineageReport = MAPPER.createObjectNode();
    ArrayNode fieldLineages = MAPPER.createArrayNode();

    for (QueryNode node : model.getNodes()) {
      String nid = String.valueOf(node.getId());
      List<Map<String, Object>> records = (List<Map<String, Object>>) (Object) fieldsNode.getOrDefault(nid, List.of());

      for (Map<String, Object> rec : records) {
        String alias = (String) rec.get("alias");
        if (alias != null && alias.toUpperCase().contains("ВИДОБЯЗАТЕЛЬСТВ")) {
          ObjectNode entry = MAPPER.createObjectNode();
          entry.put("node_id", node.getId());
          entry.put("node_name", node.getName());
          entry.put("field_alias", alias);
          entry.put("expression", (String) rec.get("expression_raw"));
          entry.put("expr_type", (String) rec.get("expr_type"));

          ArrayNode refs = MAPPER.createArrayNode();
          List<Map<String, String>> fieldRefs = (List<Map<String, String>>) rec.get("field_refs");
          if (fieldRefs != null) {
            for (Map<String, String> ref : fieldRefs) {
              ObjectNode r = MAPPER.createObjectNode();
              r.put("alias_table", ref.get("alias_table"));
              r.put("field", ref.get("field"));
              r.put("primary_table", ref.get("primary_table"));
              refs.add(r);
            }
          }
          entry.set("field_refs", refs);
          fieldLineages.add(entry);
        }
      }
    }

    lineageReport.set("field_lineage", fieldLineages);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(
      lineageDir.resolve("field_lineage.json").toFile(), lineageReport);
  }
}
