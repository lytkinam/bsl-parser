package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
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

    List<HierarchyNode> roots = model.getNodes().stream()
      .filter(n -> n.getUnionGroupId() == null && n.getUpqueryId() == null)
      .map(n -> buildNode(n, nodeById))
      .collect(Collectors.toList());

    MAPPER.writerWithDefaultPrettyPrinter().writeValue(
      lineParsDir.resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(), roots);
  }

  private HierarchyNode buildNode(LineParsNode node, Map<Integer, LineParsNode> nodeById) {
    HierarchyNode result = new HierarchyNode();
    result.setId(node.getId());
    result.setName(node.getName());

    for (int childId : node.getUnionNodesIds()) {
      LineParsNode child = nodeById.get(childId);
      if (child != null) {
        HierarchyNode childNode = buildNode(child, nodeById);
        childNode.setTypeHierarchy("union");
        result.getTableHierarchy().add(childNode);
      }
    }

    for (int childId : node.getSubqueryIds()) {
      LineParsNode child = nodeById.get(childId);
      if (child != null) {
        HierarchyNode childNode = buildNode(child, nodeById);
        childNode.setTypeHierarchy("subquery");
        result.getTableHierarchy().add(childNode);
      }
    }

    return result;
  }
}
