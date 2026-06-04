package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.github._1c_syntax.bsl.parser.sdql.model.SelectField;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LineParsFieldExtractor {

  private static final Pattern FIELD_REF_PATTERN = Pattern.compile("(?U)([\\w]+)\\.([\\w]+)");
  private static final Pattern THREE_PART_PATTERN = Pattern.compile("(?U)([\\w]+)\\.([\\w]+)\\.([\\w]+)");

  private final Map<Integer, LineParsNode> nodeById;
  private final Map<Integer, HierarchyNode> hierarchyById;

  public LineParsFieldExtractor(LineParsModel model, List<HierarchyNode> hierarchy) {
    this.nodeById = model.getNodes().stream()
      .collect(Collectors.toMap(LineParsNode::getId, n -> n));
    this.hierarchyById = hierarchy.stream()
      .filter(h -> h.getId() != null)
      .collect(Collectors.toMap(HierarchyNode::getId, h -> h));
  }

  public FieldLineageNode extract(int nodeId, String fieldAlias) {
    return buildNode(nodeId, fieldAlias);
  }

  private FieldLineageNode buildNode(int nodeId, String fieldAlias) {
    LineParsNode node = nodeById.get(nodeId);
    if (node == null) {
      return null;
    }

    SelectField field = findField(node, fieldAlias);
    if (field == null) {
      return null;
    }

    FieldLineageNode result = new FieldLineageNode();
    result.setId(node.getId());
    result.setName(node.getName());
    result.setAlias(fieldAlias);
    result.setText(field.getText());

    // Union expansion
    if (node.getUnionNodesIds() != null && !node.getUnionNodesIds().isEmpty()) {
      for (int i = 0; i < node.getUnionNodesIds().size(); i++) {
        int unionId = node.getUnionNodesIds().get(i);
        FieldLineageNode child = buildNode(unionId, fieldAlias);
        if (child != null) {
          child.setChildName("union_" + i);
          result.getChildFields().add(child);
        }
      }
      return result;
    }

    // Parse text for alias.field references
    String text = field.getText();
    if (text == null) {
      return result;
    }

    Set<TableFieldRef> candidates = new LinkedHashSet<>();
    Matcher m = FIELD_REF_PATTERN.matcher(text);
    while (m.find()) {
      candidates.add(new TableFieldRef(m.group(1), m.group(2)));
    }

    // Remove false positives from chains: for a.b.c remove b.c
    Matcher three = THREE_PART_PATTERN.matcher(text);
    while (three.find()) {
      candidates.remove(new TableFieldRef(three.group(2), three.group(3)));
    }

    for (TableFieldRef ref : candidates) {
      HierarchyNode hNode = findHierarchyNode(nodeId, ref.aliasTable);
      if (hNode == null) {
        continue;
      }
      if (hNode.getId() != null) {
        FieldLineageNode child = buildNode(hNode.getId(), ref.fieldName);
        if (child != null) {
          child.setChildName(ref.aliasTable);
          result.getChildFields().add(child);
        }
      } else {
        FieldLineageNode leaf = new FieldLineageNode();
        leaf.setChildName(ref.aliasTable);
        leaf.setName(hNode.getName());
        leaf.setSource(hNode.getSource());
        leaf.setAlias(ref.fieldName);
        leaf.setText(ref.aliasTable + "." + ref.fieldName);
        result.getChildFields().add(leaf);
      }
    }

    return result;
  }

  private SelectField findField(LineParsNode node, String alias) {
    if (node.getSelect() == null) {
      return null;
    }
    for (SelectField sf : node.getSelect()) {
      if (alias.equals(sf.getAlias())) {
        return sf;
      }
    }
    return null;
  }

  private HierarchyNode findHierarchyNode(int nodeId, String name) {
    HierarchyNode h = hierarchyById.get(nodeId);
    if (h == null || h.getTableHierarchy() == null) {
      return null;
    }
    for (HierarchyNode child : h.getTableHierarchy()) {
      if (name.equals(child.getName())) {
        return child;
      }
    }
    return null;
  }

  private static class TableFieldRef {
    final String aliasTable;
    final String fieldName;

    TableFieldRef(String aliasTable, String fieldName) {
      this.aliasTable = aliasTable;
      this.fieldName = fieldName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof TableFieldRef)) return false;
      TableFieldRef that = (TableFieldRef) o;
      return Objects.equals(aliasTable, that.aliasTable) && Objects.equals(fieldName, that.fieldName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(aliasTable, fieldName);
    }
  }
}
