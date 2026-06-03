package com.github._1c_syntax.bsl.parser.sdql;

import com.github._1c_syntax.bsl.parser.sdql.model.QueryNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NodeSplitter {

    private static final Pattern TEMP_TABLE_PATTERN = Pattern.compile(
        "(?i)ПОМЕСТИТЬ\\s+([А-Яа-яA-Za-z_][А-Яа-яA-Za-z0-9_]*)");
    private static final Pattern DROP_PATTERN = Pattern.compile(
        "(?i)УНИЧТОЖИТЬ\\s+([А-Яа-яA-Za-z_][А-Яа-яA-Za-z0-9_]*)");

    public static List<QueryNode> split(String content) {
        List<QueryNode> nodes = new ArrayList<>();
        String[] lines = content.split("\r?\n", -1);
        StringBuilder buffer = new StringBuilder();
        int id = 0;

        for (String line : lines) {
            buffer.append(line).append("\n");
            String trimmed = line.trim();
            // Разделитель: строка заканчивается на ; и это не комментарий
            if (!trimmed.isEmpty() && trimmed.endsWith(";") && !trimmed.startsWith("//")) {
                String text = trimSemicolon(buffer.toString().trim());
                if (!text.isEmpty()) {
                    nodes.add(createNode(id++, text));
                }
                buffer.setLength(0);
            }
        }

        // Остаток без закрывающей ;
        if (buffer.length() > 0) {
            String text = trimSemicolon(buffer.toString().trim());
            if (!text.isEmpty()) {
                nodes.add(createNode(id++, text));
            }
        }

        return nodes;
    }

    private static String trimSemicolon(String text) {
        if (text == null || text.isEmpty()) return text;
        // Удаляем trailing ; с возможными пробелами/переводами строк
        return text.replaceAll(";\\s*$", "").trim();
    }

    private static QueryNode createNode(int id, String text) {
        QueryNode node = new QueryNode();
        node.setId(id);
        node.setText(text);

        Matcher tempM = TEMP_TABLE_PATTERN.matcher(text);
        Matcher dropM = DROP_PATTERN.matcher(text);

        if (dropM.find()) {
            node.setType("drop_query");
            node.setName(dropM.group(1));
        } else if (tempM.find()) {
            node.setType("temp_query");
            node.setName(tempM.group(1));
        } else {
            node.setType("result");
            node.setName("Результат_" + (id + 1));
        }
        return node;
    }
}
