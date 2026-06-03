package com.github._1c_syntax.bsl.parser.sdql.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class QueryTextExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void export(List<QueryNode> nodes, Path outputDir) throws IOException {
        Path textsDir = outputDir.resolve("query_texts");
        Files.createDirectories(textsDir);

        ArrayNode index = MAPPER.createArrayNode();
        List<String> normalizedEntries = new ArrayList<>();

        for (QueryNode node : nodes) {
            String text = node.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }

            // Write .sql (no trailing semicolon)
            String sqlName = String.format("node_%d.sql", node.getId());
            Files.writeString(textsDir.resolve(sqlName), text, StandardCharsets.UTF_8);

            // Write .md
            String mdName = String.format("node_%d.md", node.getId());
            String md = "```sql\n" + text + "\n```\n";
            Files.writeString(textsDir.resolve(mdName), md, StandardCharsets.UTF_8);

            // Index entry
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("id", node.getId());
            entry.put("type", node.getType());
            entry.put("name", node.getName());
            entry.put("sql_file", "query_texts/" + sqlName);
            entry.put("md_file", "query_texts/" + mdName);
            entry.put("hash", node.getTextHash());
            entry.put("length", node.getTextLength());
            index.add(entry);

            // Collect for normalized_queries.sql (with semicolon added later)
            normalizedEntries.add("-- Node " + node.getId() + " " + node.getName() + "\n" + text);
        }

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
            textsDir.resolve("texts_index.json").toFile(), index);

        // Build normalized_queries.sql: add ; to all except the last
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < normalizedEntries.size(); i++) {
            normalized.append(normalizedEntries.get(i));
            if (i < normalizedEntries.size() - 1) {
                normalized.append(";");
            }
            normalized.append("\n\n");
        }
        Files.writeString(textsDir.resolve("normalized_queries.sql"), normalized.toString(), StandardCharsets.UTF_8);
    }
}
