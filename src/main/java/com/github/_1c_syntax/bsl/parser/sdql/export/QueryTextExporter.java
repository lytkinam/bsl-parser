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
        export(nodes, outputDir, "default");
    }

    public void export(List<QueryNode> nodes, Path outputDir, String baseName) throws IOException {
        Path textsDir = outputDir.resolve("query_texts_" + baseName);
        Files.createDirectories(textsDir);

        ArrayNode index = MAPPER.createArrayNode();
        List<QueryNode> activeNodes = new ArrayList<>();

        for (QueryNode node : nodes) {
            String text = node.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            activeNodes.add(node);

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
            entry.put("sql_file", "query_texts_" + baseName + "/" + sqlName);
            entry.put("md_file", "query_texts_" + baseName + "/" + mdName);
            entry.put("hash", node.getTextHash());
            entry.put("length", node.getTextLength());
            index.add(entry);
        }

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
            textsDir.resolve("texts_index.json").toFile(), index);

        // Build normalized_queries.sql:
        // //-- Node {id} {name}
        // {text}
        // ;
        // (no semicolon for last entry)
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < activeNodes.size(); i++) {
            QueryNode node = activeNodes.get(i);
            normalized.append("//-- Node ").append(node.getId()).append(" ").append(node.getName()).append("\n");
            normalized.append(node.getText()).append("\n");
            if (i < activeNodes.size() - 1) {
                normalized.append(";\n\n");
            }
        }
        Files.writeString(textsDir.resolve("normalized_queries.sql"), normalized.toString(), StandardCharsets.UTF_8);
    }
}
