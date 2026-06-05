package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LineParsHierarchyBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public void build(Path lineParsDir, String baseName) throws IOException {
    LineParsModel model = MAPPER.readValue(
      lineParsDir.resolve("LINE_PARS_model_" + baseName + ".json").toFile(),
      LineParsModel.class);

    Map<Integer, LineParsNode> nodeById = model.getNodes().stream()
      .collect(Collectors.toMap(LineParsNode::getId, n -> n));

    Map<String, LineParsNode> nodeByName = model.getNodes().stream()
      .collect(Collectors.toMap(LineParsNode::getName, n -> n, (a, b) -> a));

    List<HierarchyNode> allNodes = new ArrayList<>();
    for (LineParsNode node : model.getNodes()) {
      allNodes.add(buildNode(node, nodeById, nodeByName));
    }

    MAPPER.writerWithDefaultPrettyPrinter().writeValue(
      lineParsDir.resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(), allNodes);
  }

  private HierarchyNode buildNode(LineParsNode node, Map<Integer, LineParsNode> nodeById,
                                   Map<String, LineParsNode> nodeByName) {
    HierarchyNode result = new HierarchyNode();
    result.setId(node.getId());
    result.setName(node.getName());

    // 1. Data sources from FROM (including joins)
    if (node.getFrom() != null) {
      for (DataSource ds : node.getFrom()) {
        extractDataSource(ds, result, nodeByName);
      }
    }

    // 2. Union parts
    for (int childId : node.getUnionNodesIds()) {
      LineParsNode child = nodeById.get(childId);
      if (child != null) {
        HierarchyNode childNode = new HierarchyNode();
        childNode.setId(child.getId());
        childNode.setName(child.getName());
        childNode.setTypeHierarchy("union");
        result.getTableHierarchy().add(childNode);
      }
    }

    // 3. Inline subqueries (not in from[], but in subqueryIds)
    // Add any subqueryIds that are not already represented in tableHierarchy
    for (int subId : node.getSubqueryIds()) {
      boolean alreadyPresent = result.getTableHierarchy().stream()
        .anyMatch(h -> subId == (h.getId() != null ? h.getId() : -1));
      if (!alreadyPresent) {
        LineParsNode subNode = nodeById.get(subId);
        if (subNode != null) {
          HierarchyNode childNode = new HierarchyNode();
          childNode.setId(subNode.getId());
          childNode.setName(subNode.getName());
          childNode.setTypeHierarchy("from");
          childNode.setSource(subNode.getName());
          result.getTableHierarchy().add(childNode);
        }
      }
    }

    return result;
  }

  private void extractDataSource(DataSource ds, HierarchyNode parent,
                                  Map<String, LineParsNode> nodeByName) {
    if (ds == null) return;

    HierarchyNode child = null;

    if (ds.getTable() != null) {
      child = new HierarchyNode();
      child.setName(ds.getAlias() != null ? ds.getAlias() : ds.getTable());
      child.setTypeHierarchy("from");
      child.setSource(ds.getTable());
      if (!ds.getTable().contains(".")) {
        LineParsNode ref = nodeByName.get(ds.getTable());
        if (ref != null) {
          child.setId(ref.getId());
        }
      }
    } else if (ds.getVirtualTable() != null) {
      child = new HierarchyNode();
      String vtText = ds.getVirtualTable().getText();
      child.setName(ds.getAlias() != null ? ds.getAlias() : vtText);
      child.setTypeHierarchy("from");
      child.setSource(vtText);
    } else if (ds.getParameterTable() != null) {
      child = new HierarchyNode();
      child.setName(ds.getAlias() != null ? ds.getAlias() : ds.getParameterTable());
      child.setTypeHierarchy("from");
      child.setSource(ds.getParameterTable());
    } else if (ds.getExternalDataSource() != null) {
      child = new HierarchyNode();
      child.setName(ds.getAlias() != null ? ds.getAlias() : ds.getExternalDataSource());
      child.setTypeHierarchy("from");
      child.setSource(ds.getExternalDataSource());
    } else if (ds.getSubquery() != null) {
      child = new HierarchyNode();
      String subqueryName = (String) ds.getSubquery();
      child.setName(ds.getAlias() != null ? ds.getAlias() : subqueryName);
      child.setTypeHierarchy("from");
      child.setSource(subqueryName);
      LineParsNode ref = nodeByName.get(subqueryName);
      if (ref != null) {
        child.setId(ref.getId());
      }
    }

    if (child != null) {
      parent.getTableHierarchy().add(child);
    }

    // Process joins recursively
    if (ds.getJoins() != null) {
      for (JoinPart jp : ds.getJoins()) {
        extractDataSource(jp.getSource(), parent, nodeByName);
      }
    }
  }
}
