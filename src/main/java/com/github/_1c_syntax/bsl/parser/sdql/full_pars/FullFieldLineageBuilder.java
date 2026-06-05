package com.github._1c_syntax.bsl.parser.sdql.full_pars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.HavingBlock;
import com.github._1c_syntax.bsl.parser.sdql.model.WhereBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Comparator;

public class FullFieldLineageBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Map<Integer, FullParsNode> nodeById;
  private int nextFieldId = 100000; // for synthetic fields added from group_by

  /**
   * Build full_field_lineage for explicitly specified target fields.
   */
  public void build(Path fullParsDir, String baseName, List<TargetField> targets) throws IOException {
    FullParsModel fullParsModel = loadModel(fullParsDir, baseName);

    for (TargetField target : targets) {
      buildSingleTarget(fullParsDir, baseName, target);
    }
  }

  /**
   * Build full_field_lineage for all fields of target nodes (result or last temp_query),
   * analogous to LineParsFieldLineageBuilder.
   */
  public void build(Path fullParsDir, String baseName) throws IOException {
    FullParsModel fullParsModel = loadModel(fullParsDir, baseName);

    List<FullParsNode> targetNodes = selectTargetNodes(fullParsModel);

    for (FullParsNode node : targetNodes) {
      if (node.getSelect() == null) {
        continue;
      }
      for (FullParsSelectField sf : node.getSelect()) {
        if (sf.getAlias() == null) {
          continue;
        }
        TargetField target = new TargetField(node.getId(), node.getName(), List.of(sf.getAlias()));
        buildSingleTarget(fullParsDir, baseName, target);
      }
    }
  }

  private FullParsModel loadModel(Path fullParsDir, String baseName) throws IOException {
    FullParsModel fullParsModel = MAPPER.readValue(
      fullParsDir.resolve("FULL_PARS_model_" + baseName + ".json").toFile(),
      FullParsModel.class);

    this.nodeById = fullParsModel.getNodes().stream()
      .collect(Collectors.toMap(FullParsNode::getId, n -> n));

    // Determine max existing field_id for synthetic fields
    int maxFieldId = 0;
    for (FullParsNode node : fullParsModel.getNodes()) {
      if (node.getSelect() != null) {
        for (FullParsSelectField sf : node.getSelect()) {
          if (sf.getFieldId() != null && sf.getFieldId() > maxFieldId) {
            maxFieldId = sf.getFieldId();
          }
        }
      }
    }
    this.nextFieldId = maxFieldId + 1;

    return fullParsModel;
  }

  private void buildSingleTarget(Path fullParsDir, String baseName, TargetField target) throws IOException {
    LinkedHashMap<Integer, FullParsNode> resultMap = new LinkedHashMap<>();
    buildLineage(target.getNodeId(), new ArrayList<>(target.getAliases()), resultMap);

    List<FullParsNode> resultList = new ArrayList<>(resultMap.values());
    if (resultList.isEmpty()) {
      return;
    }

    Path lineageDir = fullParsDir.getParent().resolve("full_field_lineage")
      .resolve(baseName)
      .resolve(target.getNodeId() + "_" + target.getNodeName());
    Files.createDirectories(lineageDir);

    String fileName = "FFL_" + baseName + "_" + target.getNodeId() + "_" + target.getNodeName()
      + "_" + String.join("_", target.getAliases()) + ".json";
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(
      lineageDir.resolve(fileName).toFile(), resultList);
  }

  private List<FullParsNode> selectTargetNodes(FullParsModel model) {
    List<FullParsNode> resultNodes = model.getNodes().stream()
      .filter(n -> "result".equals(n.getType()))
      .collect(Collectors.toList());

    if (!resultNodes.isEmpty()) {
      return resultNodes;
    }

    return model.getNodes().stream()
      .filter(n -> "temp_query".equals(n.getType()))
      .max(Comparator.comparingInt(FullParsNode::getId))
      .map(List::of)
      .orElse(List.of());
  }

  private void buildLineage(int nodeId, List<String> aliases,
                            LinkedHashMap<Integer, FullParsNode> resultMap) {
    FullParsNode node = nodeById.get(nodeId);
    if (node == null) {
      return;
    }

    FullParsNode result;
    if (resultMap.containsKey(nodeId)) {
      result = resultMap.get(nodeId);
      // Extend select with new aliases
      for (String alias : aliases) {
        if (!hasAliasInSelect(result, alias)) {
          FullParsSelectField field = findFieldInNode(node, alias);
          if (field != null) {
            result.getSelect().add(cloneField(field));
          }
        }
      }
    } else {
      result = createNode(node, aliases);
      resultMap.put(nodeId, result);
    }

    // Handle UNION virtual parent
    if (node.getUnionNodesIds() != null && !node.getUnionNodesIds().isEmpty()) {
      for (int unionId : node.getUnionNodesIds()) {
        buildLineage(unionId, aliases, resultMap);
      }
      return;
    }

    // Collect all child_fields from select + where + joins + group_by + having
    List<FullParsChildField> fullChildFields = new ArrayList<>();

    for (FullParsSelectField sf : result.getSelect()) {
      if (sf.getChildFields() != null) {
        for (FullParsChildField child : sf.getChildFields()) {
          if (child.getNodeId() != null) {
            fullChildFields.add(child);
          }
        }
      }
    }
    for (FullParsConditionField cf : result.getWhereFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            fullChildFields.add(child);
          }
        }
      }
    }
    for (FullParsJoinCondition jc : result.getJoinFields()) {
      for (FullParsConditionField cf : jc.getConditionFields()) {
        if (cf.getChildFields() != null) {
          for (FullParsChildField child : cf.getChildFields()) {
            if (child.getNodeId() != null) {
              fullChildFields.add(child);
            }
          }
        }
      }
    }
    for (FullParsConditionField cf : result.getGroupByFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            fullChildFields.add(child);
          }
        }
      }
    }
    for (FullParsConditionField cf : result.getHavingFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            fullChildFields.add(child);
          }
        }
      }
    }

    // Group by node_id
    Map<Integer, List<FullParsChildField>> grouped = fullChildFields.stream()
      .collect(Collectors.groupingBy(FullParsChildField::getNodeId, LinkedHashMap::new, Collectors.toList()));

    // Recursive calls
    for (Map.Entry<Integer, List<FullParsChildField>> entry : grouped.entrySet()) {
      int usedNodeId = entry.getKey();
      List<String> usedAliases = entry.getValue().stream()
        .map(FullParsChildField::getAlias)
        .distinct()
        .collect(Collectors.toList());
      buildLineage(usedNodeId, usedAliases, resultMap);
    }
  }

  private FullParsNode createNode(FullParsNode source, List<String> aliases) {
    FullParsNode result = new FullParsNode();
    result.setId(source.getId());
    result.setSdblId(source.getSdblId());
    result.setName(source.getName());
    result.setType(source.getType());
    result.setInto(source.getInto());
    result.setFrom(source.getFrom());
    if (source.getWhere() != null) {
      WhereBlock wb = new WhereBlock();
      wb.setText(source.getWhere().getText());
      wb.setSubqueryIds(source.getWhere().getSubqueryIds());
      result.setWhere(wb);
    }
    result.setGroupBy(source.getGroupBy());
    if (source.getHaving() != null) {
      HavingBlock hb = new HavingBlock();
      hb.setText(source.getHaving().getText());
      hb.setSubqueryIds(source.getHaving().getSubqueryIds());
      result.setHaving(hb);
    }
    result.setForUpdate(source.getForUpdate());
    result.setIndexBy(source.getIndexBy());
    result.setIndexBySets(source.getIndexBySets());
    result.setLimitations(source.getLimitations());
    result.setAutoorder(source.getAutoorder());
    result.setOrderBy(source.getOrderBy());
    result.setUpqueryId(source.getUpqueryId());
    result.setSubqueryIds(source.getSubqueryIds());
    result.setUnionNodesIds(source.getUnionNodesIds());
    result.setUnionGroupId(source.getUnionGroupId());
    result.setUnionType(source.getUnionType());

    // Select: only requested aliases + group_by fields not in select
    List<FullParsSelectField> selectFields = new ArrayList<>();
    for (String alias : aliases) {
      FullParsSelectField f = findFieldInNode(source, alias);
      if (f != null) {
        selectFields.add(cloneField(f));
      }
    }

    // Add group_by fields that are not in select
    if (source.getGroupByFields() != null) {
      for (FullParsConditionField gbf : source.getGroupByFields()) {
        if (gbf.getChildFields() != null && !gbf.getChildFields().isEmpty()) {
          for (FullParsChildField child : gbf.getChildFields()) {
            if (child.getAlias() != null && !hasAliasInSelectList(selectFields, child.getAlias())) {
              FullParsSelectField synthetic = new FullParsSelectField();
              synthetic.setFieldId(nextFieldId++);
              synthetic.setAlias(child.getAlias());
              synthetic.setText(gbf.getText());
              synthetic.setChildFields(new ArrayList<>(gbf.getChildFields()));
              selectFields.add(synthetic);
            }
          }
        }
      }
    }

    result.setSelect(selectFields);

    // Copy all where_fields, group_by_fields, having_fields, join_conditions
    if (source.getWhere() != null && source.getWhere().getFields() != null) {
      result.setWhereFields(cloneConditionFields(source.getWhere().getFields()));
    }
    result.setGroupByFields(cloneConditionFields(source.getGroupByFields()));
    if (source.getHaving() != null && source.getHaving().getFields() != null) {
      result.setHavingFields(cloneConditionFields(source.getHaving().getFields()));
    }
    result.setJoinFields(cloneJoinConditions(source.getJoinFields()));

    return result;
  }

  private boolean hasAliasInSelect(FullParsNode node, String alias) {
    if (node.getSelect() == null) return false;
    for (FullParsSelectField sf : node.getSelect()) {
      if (alias.equals(sf.getAlias())) {
        return true;
      }
    }
    return false;
  }

  private boolean hasAliasInSelectList(List<FullParsSelectField> list, String alias) {
    for (FullParsSelectField sf : list) {
      if (alias.equals(sf.getAlias())) {
        return true;
      }
    }
    return false;
  }

  private FullParsSelectField findFieldInNode(FullParsNode node, String alias) {
    if (node.getSelect() == null) return null;
    for (FullParsSelectField sf : node.getSelect()) {
      if (alias.equals(sf.getAlias())) {
        return sf;
      }
    }
    return null;
  }

  private FullParsSelectField cloneField(FullParsSelectField source) {
    FullParsSelectField f = new FullParsSelectField();
    f.setFieldId(source.getFieldId());
    f.setAlias(source.getAlias());
    f.setText(source.getText());
    if (source.getChildFields() != null) {
      f.setChildFields(source.getChildFields().stream()
        .map(this::cloneChildField)
        .collect(Collectors.toList()));
    }
    return f;
  }

  private FullParsChildField cloneChildField(FullParsChildField source) {
    FullParsChildField c = new FullParsChildField();
    c.setFieldId(source.getFieldId());
    c.setAlias(source.getAlias());
    c.setNodeId(source.getNodeId());
    c.setNodeName(source.getNodeName());
    c.setSource(source.getSource());
    return c;
  }

  private List<FullParsConditionField> cloneConditionFields(List<FullParsConditionField> source) {
    if (source == null) return new ArrayList<>();
    return source.stream().map(this::cloneConditionField).collect(Collectors.toList());
  }

  private FullParsConditionField cloneConditionField(FullParsConditionField source) {
    FullParsConditionField c = new FullParsConditionField();
    c.setText(source.getText());
    if (source.getChildFields() != null) {
      c.setChildFields(source.getChildFields().stream()
        .map(this::cloneChildField)
        .collect(Collectors.toList()));
    }
    return c;
  }

  private List<FullParsJoinCondition> cloneJoinConditions(List<FullParsJoinCondition> source) {
    if (source == null) return new ArrayList<>();
    return source.stream().map(this::cloneJoinCondition).collect(Collectors.toList());
  }

  private FullParsJoinCondition cloneJoinCondition(FullParsJoinCondition source) {
    FullParsJoinCondition j = new FullParsJoinCondition();
    j.setJoinType(source.getJoinType());
    j.setSource(source.getSource());
    j.setCondition(source.getCondition());
    j.setConditionFields(cloneConditionFields(source.getConditionFields()));
    return j;
  }

  public static class TargetField {
    private final int nodeId;
    private final String nodeName;
    private final List<String> aliases;

    public TargetField(int nodeId, String nodeName, List<String> aliases) {
      this.nodeId = nodeId;
      this.nodeName = nodeName;
      this.aliases = aliases;
    }

    public int getNodeId() { return nodeId; }
    public String getNodeName() { return nodeName; }
    public List<String> getAliases() { return aliases; }
  }
}
