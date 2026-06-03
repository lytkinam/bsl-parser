package com.github._1c_syntax.bsl.parser.sdql;

import com.github._1c_syntax.bsl.parser.SDBLParser;
import com.github._1c_syntax.bsl.parser.SDBLTokenizer;
import com.github._1c_syntax.bsl.parser.sdql.io.ModelJsonMapper;
import com.github._1c_syntax.bsl.parser.sdql.io.NodesJsonMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.*;
import com.github._1c_syntax.bsl.parser.sdql.visitor.QueryPackageVisitor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public class SdqlQueryPackageAnalyzer {

    private QueryModel model;
    private List<QueryNode> fullNodes;

    public QueryModel getModel() { return model; }
    public List<QueryNode> getFullNodes() { return fullNodes; }

    public void analyze(File sqlFile, File outputDir) throws Exception {
        String content = Files.readString(sqlFile.toPath());
        SDBLTokenizer tokenizer = new SDBLTokenizer(content);
        SDBLParser.QueryPackageContext ast = tokenizer.getAst();
        QueryPackageVisitor visitor = new QueryPackageVisitor(content);
        List<QueryAst> asts = visitor.visitQueryPackage(ast);

        List<QueryNode> nodes = new ArrayList<>();
        int id = 0;
        for (QueryAst qast : asts) {
            QueryNode node = new QueryNode();
            node.setId(id++);
            node.setQuery(qast);
            String text = extractText(content, qast);
            node.setText(text);
            if (qast.getType() == null) {
                node.setType("unknown");
            } else if ("drop".equals(qast.getType())) {
                node.setType("drop_query");
                node.setName(qast.getInto());
            } else if (qast.getInto() != null && !qast.getInto().isEmpty()) {
                node.setType("temp_query");
                node.setName(qast.getInto());
            } else {
                node.setType("result");
                node.setName("Результат_" + id);
            }
            nodes.add(node);
        }

        List<QueryEdge> edges = resolveEdges(nodes);
        computeHashes(nodes);

        // Save full nodes (with text) to nodes.json
        fullNodes = new ArrayList<>();
        for (QueryNode n : nodes) {
            QueryNode copy = new QueryNode();
            copy.setId(n.getId());
            copy.setType(n.getType());
            copy.setName(n.getName());
            copy.setText(n.getText());
            copy.setTextHash(n.getTextHash());
            copy.setTextLength(n.getTextLength());
            copy.setQuery(n.getQuery());
            fullNodes.add(copy);
        }
        outputDir.mkdirs();
        NodesJsonMapper.write(fullNodes, new File(outputDir, "nodes.json"));

        // Strip text for model.json
        stripTextsForJson(nodes);
        model = new QueryModel();
        model.setNodes(nodes);
        model.setEdges(edges);
        model.setSourceHash(sha256(content));
        model.setSourceLength(content.length());
        ModelJsonMapper.write(model, Path.of(outputDir.getAbsolutePath(), "model.json"));
    }

    private String extractText(String content, QueryAst qast) {
        int start = qast.getStartIndex();
        int end = qast.getEndIndex();
        if (start >= 0 && end > start && end <= content.length()) {
            return content.substring(start, end);
        }
        return "";
    }

    private List<QueryEdge> resolveEdges(List<QueryNode> nodes) {
        Map<String, Integer> tempTables = new HashMap<>();
        for (QueryNode node : nodes) {
            if ("temp_query".equals(node.getType()) && node.getName() != null) {
                tempTables.put(node.getName().toUpperCase(), node.getId());
            }
        }
        List<QueryEdge> edges = new ArrayList<>();
        for (QueryNode node : nodes) {
            QueryAst qast = node.getQuery();
            if (qast == null) continue;
            Set<String> seen = new HashSet<>();
            if (qast.getFrom() != null) {
                for (DataSource ds : qast.getFrom()) {
                    checkSource(ds, node, tempTables, edges, seen);
                    if (ds.getJoins() != null) {
                        for (JoinPart jp : ds.getJoins()) {
                            checkSource(jp.getSource(), node, tempTables, edges, seen);
                        }
                    }
                }
            }
        }
        return edges;
    }

    private void checkSource(DataSource ds, QueryNode node, Map<String, Integer> tempTables,
                             List<QueryEdge> edges, Set<String> seen) {
        if (ds == null) return;
        String name = ds.getTable();
        if (name != null && name.toUpperCase().startsWith("ВТ_")) {
            Integer fromId = tempTables.get(name.toUpperCase());
            if (fromId != null) {
                String key = fromId + "->" + node.getId();
                if (!seen.contains(key)) {
                    seen.add(key);
                    QueryEdge edge = new QueryEdge();
                    edge.setFrom(fromId);
                    edge.setTo(node.getId());
                    edge.setFromName(name);
                    edge.setToName(node.getName());
                    edges.add(edge);
                }
            }
        }
    }

    private void computeHashes(List<QueryNode> nodes) throws Exception {
        for (QueryNode node : nodes) {
            String text = node.getText();
            if (text != null && !text.isEmpty()) {
                node.setTextHash(sha256(text));
                node.setTextLength(text.length());
            }
        }
    }

    private void stripTextsForJson(List<QueryNode> nodes) {
        for (QueryNode node : nodes) {
            if ("temp_query".equals(node.getType()) || "select".equals(node.getType())) {
                node.setText(null);
            }
        }
    }

    private String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
