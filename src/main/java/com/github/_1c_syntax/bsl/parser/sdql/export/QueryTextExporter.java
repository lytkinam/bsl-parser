package com.github._1c_syntax.bsl.parser.sdql.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class QueryTextExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void export(List<QueryNode> nodes, Path outputDir) throws IOException {
        Path textsDir = outputDir.resolve("query_texts");
        Files.createDirectories(textsDir);

        ArrayNode index = MAPPER.createArrayNode();
        StringBuilder normalized = new StringBuilder();

        for (QueryNode node : nodes) {
            String text = node.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }

            // Write .sql
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

            // normalized_queries.sql
            normalized.append("-- Node ").append(node.getId()).append(" ").append(node.getName()).append("\n");
            normalized.append(text).append("\n\n");
        }

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
            textsDir.resolve("texts_index.json").toFile(), index);
        Files.writeString(textsDir.resolve("normalized_queries.sql"), normalized.toString(), StandardCharsets.UTF_8);
    }
}
