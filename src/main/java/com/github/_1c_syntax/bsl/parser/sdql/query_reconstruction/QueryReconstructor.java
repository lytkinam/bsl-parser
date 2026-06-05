package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class QueryReconstructor {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path fullParsDir, String baseName) throws IOException {
    FullParsModel fullParsModel = MAPPER.readValue(
      fullParsDir.resolve("FULL_PARS_model_" + baseName + ".json").toFile(),
      FullParsModel.class);

    Map<Integer, FullParsNode> fullParsById = fullParsModel.getNodes().stream()
      .collect(Collectors.toMap(FullParsNode::getId, n -> n));

    // Find all FFL files for this baseName
    Path fflBaseDir = fullParsDir.getParent().resolve("full_field_lineage").resolve(baseName);
    if (!Files.exists(fflBaseDir)) {
      return;
    }

    List<Path> fflDirs = Files.list(fflBaseDir)
      .filter(Files::isDirectory)
      .collect(Collectors.toList());

    for (Path nodeDir : fflDirs) {
      processFflDir(nodeDir, fullParsById, baseName, fullParsDir);
    }
  }

  private void processFflDir(Path nodeDir, Map<Integer, FullParsNode> fullParsById,
                              String baseName, Path fullParsDir) throws IOException {
    List<Path> fflFiles = Files.list(nodeDir)
      .filter(p -> p.toString().endsWith(".json"))
      .collect(Collectors.toList());

    for (Path fflFile : fflFiles) {
      List<FullParsNode> fflNodes = MAPPER.readValue(fflFile.toFile(),
        new TypeReference<List<FullParsNode>>() {});

      if (fflNodes.isEmpty()) {
        continue;
      }

      // Extract alias from filename: FFL_<base>_<nodeId>_<nodeName>_<alias>.json
      String fileName = fflFile.getFileName().toString();
      String alias = extractAliasFromFileName(fileName, baseName);

      String sql = reconstruct(fflNodes, fullParsById);

      // Output path: RESTORED_QUERIES/<baseName>/<nodeId>_<nodeName>/<alias>.sql
      Path outputDir = fullParsDir.getParent().resolve("RESTORED_QUERIES")
        .resolve(baseName)
        .resolve(nodeDir.getFileName().toString());
      Files.createDirectories(outputDir);

      Files.writeString(outputDir.resolve(alias + ".sql"), sql);
    }
  }

  private String extractAliasFromFileName(String fileName, String baseName) {
    String prefix = "FFL_" + baseName + "_";
    if (fileName.startsWith(prefix)) {
      String rest = fileName.substring(prefix.length());
      rest = rest.substring(0, rest.length() - 5);
      int firstUnderscore = rest.indexOf('_');
      if (firstUnderscore > 0) {
        String afterNodeId = rest.substring(firstUnderscore + 1);
        int aliasStart = afterNodeId.indexOf('_');
        if (aliasStart > 0) {
          return afterNodeId.substring(aliasStart + 1);
        }
      }
    }
    String result = fileName;
    if (result.startsWith(prefix)) {
      result = result.substring(prefix.length());
    }
    if (result.endsWith(".json")) {
      result = result.substring(0, result.length() - 5);
    }
    int first = result.indexOf('_');
    if (first > 0) {
      int second = result.indexOf('_', first + 1);
      if (second > 0) {
        return result.substring(second + 1);
      }
    }
    return result;
  }

  public String reconstruct(List<FullParsNode> fflNodes) {
    return reconstruct(fflNodes, null);
  }

  public String reconstruct(List<FullParsNode> fflNodes, Map<Integer, FullParsNode> fullParsById) {
    TopologicalSorter sorter = new TopologicalSorter();
    List<Integer> order = sorter.sort(fflNodes);

    Map<Integer, FullParsNode> fflById = fflNodes.stream()
      .collect(Collectors.toMap(FullParsNode::getId, n -> n));

    QueryNodeBuilder nodeBuilder = fullParsById != null
      ? new QueryNodeBuilder(fflById, fullParsById)
      : new QueryNodeBuilder(fflById);
    SqlGenerator sqlGenerator = new SqlGenerator();

    List<String> queries = new ArrayList<>();

    for (int nodeId : order) {
      FullParsNode fflNode = fflById.get(nodeId);
      if (fflNode == null) continue;

      // Skip sub_query nodes — they are inlined into parent queries
      if ("sub_query".equals(fflNode.getType())) {
        continue;
      }

      // Skip physical-only leaf nodes
      if (isPhysicalLeaf(fflNode, fflById)) {
        continue;
      }

      // Skip drop queries
      if ("drop_query".equals(fflNode.getType())) {
        continue;
      }

      RestoredQueryNode restored = nodeBuilder.build(fflNode);
      String sql = sqlGenerator.generate(restored);
      if (!sql.isEmpty()) {
        queries.add(sql);
      }
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < queries.size(); i++) {
      sb.append(queries.get(i));
      if (i < queries.size() - 1) {
        sb.append(";\n\n");
        sb.append("////////////////////////////////////////////////////////////////////////////////\n\n");
      } else {
        sb.append(";\n");
      }
    }
    return sb.toString();
  }

  private boolean isPhysicalLeaf(FullParsNode node, Map<Integer, FullParsNode> fflById) {
    // Nodes that create temp tables are never leaves
    if (node.getInto() != null) {
      return false;
    }
    // temp_query always produces a query
    if ("temp_query".equals(node.getType())) {
      return false;
    }
    // sub_query is inlined, not a separate query
    // But sub_query with union_nodes_ids generates UNION — not a leaf
    if ("sub_query".equals(node.getType())) {
      return node.getUnionNodesIds() == null || node.getUnionNodesIds().isEmpty();
    }
    // Virtual union parents (with union_nodes_ids) are not leaves — they generate UNION
    if ("union_query".equals(node.getType()) && node.getUnionNodesIds() != null && !node.getUnionNodesIds().isEmpty()) {
      return false;
    }
    // Union parts (union_query with union_group_id set) are parts of a UNION — not separate queries
    if ("union_query".equals(node.getType()) && node.getUnionGroupId() != null) {
      return true;
    }
    // select nodes also produce queries
    if ("select".equals(node.getType())) {
      return false;
    }

    // A node is a leaf if it has no references to other nodes (all child_fields have node_id=null)
    boolean hasNodeRefs = hasNodeIdRefs(node);
    return !hasNodeRefs;
  }

  private boolean hasNodeIdRefs(FullParsNode node) {
    if (node.getSelect() != null) {
      for (var sf : node.getSelect()) {
        if (sf.getChildFields() != null) {
          for (var child : sf.getChildFields()) {
            if (child.getNodeId() != null) return true;
          }
        }
      }
    }
    for (var cf : node.getWhereFields()) {
      if (cf.getChildFields() != null) {
        for (var child : cf.getChildFields()) {
          if (child.getNodeId() != null) return true;
        }
      }
    }
    for (var jc : node.getJoinFields()) {
      for (var cf : jc.getConditionFields()) {
        if (cf.getChildFields() != null) {
          for (var child : cf.getChildFields()) {
            if (child.getNodeId() != null) return true;
          }
        }
      }
    }
    for (var cf : node.getGroupByFields()) {
      if (cf.getChildFields() != null) {
        for (var child : cf.getChildFields()) {
          if (child.getNodeId() != null) return true;
        }
      }
    }
    for (var cf : node.getHavingFields()) {
      if (cf.getChildFields() != null) {
        for (var child : cf.getChildFields()) {
          if (child.getNodeId() != null) return true;
        }
      }
    }
    return false;
  }
}
