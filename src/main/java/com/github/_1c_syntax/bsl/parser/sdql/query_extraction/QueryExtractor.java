package com.github._1c_syntax.bsl.parser.sdql.query_extraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryNode;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.SqlGenerator;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.RestoredQueryNode;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.TopologicalSorter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class QueryExtractor {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path fullParsDir, String baseName) throws IOException {
    // 1. Load FULL_PARS model
    FullParsModel fullParsModel = MAPPER.readValue(
      fullParsDir.resolve("FULL_PARS_model_" + baseName + ".json").toFile(),
      FullParsModel.class);

    Map<Integer, FullParsNode> fullParsById = fullParsModel.getNodes().stream()
      .collect(Collectors.toMap(FullParsNode::getId, n -> n));

    // 2. Load primary texts from sdbl_parse_nodes
    Map<Integer, String> primaryTexts = loadPrimaryTexts(fullParsDir, baseName);

    // 3. Topological sort
    TopologicalSorter sorter = new TopologicalSorter();
    List<Integer> order = sorter.sort(fullParsModel.getNodes());

    // 4. Build SQL for each node
    QueryBuilder queryBuilder = new QueryBuilder(fullParsById);
    SqlGenerator sqlGenerator = new SqlGenerator();
    QueryVerifier verifier = new QueryVerifier();

    Path extractedDir = fullParsDir.getParent().resolve("EXTRACTED_QUERIES").resolve(baseName);
    Files.createDirectories(extractedDir);

    VerificationReport report = new VerificationReport();
    report.setBaseName(baseName);

    for (int nodeId : order) {
      FullParsNode fpNode = fullParsById.get(nodeId);
      if (fpNode == null) continue;

      // Skip drop queries
      if ("drop_query".equals(fpNode.getType())) {
        continue;
      }

      // Skip union_query nodes — they are parts of a UNION parent and don't have separate primary texts
      if ("union_query".equals(fpNode.getType())) {
        continue;
      }

      // Skip physical-only leaf nodes (no separate SQL)
      if (isPhysicalLeaf(fpNode)) {
        continue;
      }

      // Build query
      RestoredQueryNode restored = queryBuilder.build(fpNode);
      String sql = sqlGenerator.generate(restored);

      // For non-sub_query nodes, add trailing semicolon
      if (!"sub_query".equals(fpNode.getType())) {
        sql = sql + ";";
      }

      // Write extracted SQL
      String fileName = fpNode.getId() + "_" + fpNode.getName() + ".sql";
      Files.writeString(extractedDir.resolve(fileName), sql);

      // Verify against primary
      String primaryText = primaryTexts.get(fpNode.getSdblId());
      NodeVerificationResult vr;
      if (primaryText != null) {
        vr = verifier.verify(fpNode.getId(), fpNode.getName(), sql, primaryText);
      } else {
        vr = new NodeVerificationResult();
        vr.setId(fpNode.getId());
        vr.setName(fpNode.getName());
        vr.setStatus("skipped");
        vr.setDifferences(List.of("Primary text not found for sdblId=" + fpNode.getSdblId()));
      }
      report.getNodes().add(vr);
    }

    report.setTotalNodes(report.getNodes().size());
    report.setMatched((int) report.getNodes().stream().filter(n -> "matched".equals(n.getStatus())).count());
    report.setMismatched((int) report.getNodes().stream().filter(n -> "mismatched".equals(n.getStatus())).count());

    // 5. Write verification report
    Path verificationDir = fullParsDir.getParent().resolve("VERIFICATION").resolve(baseName);
    Files.createDirectories(verificationDir);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(
      verificationDir.resolve("verification_report.json").toFile(), report);
  }

  private Map<Integer, String> loadPrimaryTexts(Path fullParsDir, String baseName) throws IOException {
    Map<Integer, String> result = new HashMap<>();
    Path nodesFile = fullParsDir.getParent().resolve("SDBL_PARS")
      .resolve("sdbl_parse_nodes_" + baseName + ".json");
    if (!Files.exists(nodesFile)) {
      return result;
    }
    List<QueryNode> nodes = MAPPER.readValue(nodesFile.toFile(), new TypeReference<List<QueryNode>>() {});
    for (QueryNode node : nodes) {
      if (node.getText() != null) {
        result.put(node.getId(), node.getText());
      }
    }
    return result;
  }

  private boolean isPhysicalLeaf(FullParsNode node) {
    if (node.getInto() != null) {
      return false;
    }
    if ("temp_query".equals(node.getType())) {
      return false;
    }
    if ("sub_query".equals(node.getType())) {
      return false;
    }
    if ("union_query".equals(node.getType()) && node.getUnionNodesIds() != null && !node.getUnionNodesIds().isEmpty()) {
      return false;
    }
    if ("select".equals(node.getType())) {
      return false;
    }

    // A node is a leaf if it has no references to other nodes
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
