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
        analyze(sqlFile, outputDir, baseName(sqlFile));
    }

    public void analyze(File sqlFile, File outputDir, String baseName) throws Exception {
        String content = Files.readString(sqlFile.toPath());

        // 1. PRIMARY PASS: split by ; → nodes.json (full text, comments, whitespace)
        fullNodes = NodeSplitter.split(content);
        computeHashes(fullNodes);
        outputDir.mkdirs();
        NodesJsonMapper.write(fullNodes, new File(outputDir, "sdbl_parse_nodes_" + baseName + ".json"));

        // 2. SECONDARY PASS: parse entire file via ANTLR → AST per node
        SDBLTokenizer tokenizer = new SDBLTokenizer(content);
        SDBLParser.QueryPackageContext ast = tokenizer.getAst();
        QueryPackageVisitor visitor = new QueryPackageVisitor(content);
        List<QueryAst> asts = visitor.visitQueryPackage(ast);

        // 3. MERGE: attach AST to primary nodes by index
        List<QueryNode> modelNodes = new ArrayList<>();
        for (int i = 0; i < fullNodes.size(); i++) {
            QueryNode primary = fullNodes.get(i);
            QueryNode mn = new QueryNode();
            mn.setId(primary.getId());
            mn.setType(primary.getType());
            mn.setName(primary.getName());
            mn.setTextHash(primary.getTextHash());
            mn.setTextLength(primary.getTextLength());
            if (i < asts.size()) {
                mn.setQuery(asts.get(i));
            }
            modelNodes.add(mn);
        }

        // 4. Resolve edges from AST
        List<QueryEdge> edges = resolveEdges(modelNodes);

        // 5. Strip text for model.json (temp_query/select)
        stripTextsForJson(modelNodes);
        model = new QueryModel();
        model.setNodes(modelNodes);
        model.setEdges(edges);
        model.setSourceHash(sha256(content));
        model.setSourceLength(content.length());
        ModelJsonMapper.write(model, Path.of(outputDir.getAbsolutePath(), "sdbl_parser_model_" + baseName + ".json"));
    }

    private static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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

    private String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
