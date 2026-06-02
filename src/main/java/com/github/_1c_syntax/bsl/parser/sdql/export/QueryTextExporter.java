package com.github._1c_syntax.bsl.parser.sdql.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryTextExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern QUERY_SEPARATOR = Pattern.compile(
        "(?i)(SELECT\\s+|ВЫБРАТЬ\\s+|УНИЧТОЖИТЬ\\s+|DROP\\s+)");

    public void export(QueryModel model, java.io.File inputFile, Path outputDir) throws IOException {
        Path textsDir = outputDir.resolve("query_texts");
        Files.createDirectories(textsDir);

        String content = Files.readString(inputFile.toPath(), StandardCharsets.UTF_8);
        List<String> rawQueries = splitQueries(content);

        ArrayNode index = MAPPER.createArrayNode();
        StringBuilder normalized = new StringBuilder();

        int qidx = 0;
        for (QueryNode node : model.getNodes()) {
            String text = null;
            if (qidx < rawQueries.size()) {
                text = rawQueries.get(qidx).trim();
            }
            qidx++;

            if (text == null || text.isEmpty()) continue;

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

    private List<String> splitQueries(String content) {
        List<String> result = new ArrayList<>();
        Matcher m = QUERY_SEPARATOR.matcher(content);
        int last = 0;
        while (m.find()) {
            if (last < m.start()) {
                String prev = content.substring(last, m.start()).trim();
                if (!prev.isEmpty()) {
                    if (!result.isEmpty()) {
                        result.set(result.size() - 1,
                            result.get(result.size() - 1) + "\n" + prev);
                    }
                }
            }
            last = m.start();
        }
        if (last < content.length()) {
            String tail = content.substring(last).trim();
            if (!tail.isEmpty()) result.add(tail);
        }
        // merge semicolons
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String q : result) {
            buf.append(q).append("\n");
            if (q.trim().endsWith(";")) {
                merged.add(buf.toString().trim());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) merged.add(buf.toString().trim());
        return merged.isEmpty() ? List.of(content.trim()) : merged;
    }
}
