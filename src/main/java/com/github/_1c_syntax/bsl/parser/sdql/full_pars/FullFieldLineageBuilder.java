package com.github._1c_syntax.bsl.parser.sdql.full_pars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.HavingBlock;
import com.github._1c_syntax.bsl.parser.sdql.model.JoinPart;
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

    // Collect subquery_ids from where, having, and virtualTable in from
    List<Integer> subqueryIds = new ArrayList<>();
    if (result.getWhere() != null && result.getWhere().getSubqueryIds() != null) {
      subqueryIds.addAll(result.getWhere().getSubqueryIds());
    }
    if (result.getHaving() != null && result.getHaving().getSubqueryIds() != null) {
      subqueryIds.addAll(result.getHaving().getSubqueryIds());
    }
    if (result.getFrom() != null) {
      for (DataSource ds : result.getFrom()) {
        if (ds.getVirtualTable() != null && ds.getVirtualTable().getSubqueryIds() != null) {
          subqueryIds.addAll(ds.getVirtualTable().getSubqueryIds());
        }
      }
    }

    // Group by node_id
    Map<Integer, List<FullParsChildField>> grouped = fullChildFields.stream()
      .collect(Collectors.groupingBy(FullParsChildField::getNodeId, LinkedHashMap::new, Collectors.toList()));

    // Recursive calls for child_fields
    for (Map.Entry<Integer, List<FullParsChildField>> entry : grouped.entrySet()) {
      int usedNodeId = entry.getKey();
      List<String> usedAliases = entry.getValue().stream()
        .map(FullParsChildField::getAlias)
        .distinct()
        .collect(Collectors.toList());
      buildLineage(usedNodeId, usedAliases, resultMap);
    }

    // Recursive calls for subqueries (inline subqueries in WHERE/HAVING/VirtualTable)
    for (int sqId : subqueryIds) {
      buildLineage(sqId, List.of(), resultMap);
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
    // Skip for UNION parts — adding group_by fields would break field count consistency
    if (source.getGroupByFields() != null && source.getUnionGroupId() == null) {
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

    filterUnusedJoins(result);

    return result;
  }

  /**
   * Filter out unused LEFT JOINs from the node.
   * A LEFT JOIN is unused if neither its alias nor its table appear in
   * SELECT / WHERE / GROUP BY / HAVING fields, nor in condition_fields of
   * subsequent (to the right) used JOINs.
   *
   * INNER JOINs are never removed — they filter rows.
   *
   * This must run BEFORE buildLineage() collects fullChildFields for recursion,
   * so that child nodes referenced only by removed JOINs are not added to resultMap.
   */
  private void filterUnusedJoins(FullParsNode node) {
    if (node.getFrom() == null || node.getFrom().isEmpty()) {
      return;
    }

    DataSource mainSource = node.getFrom().get(0);
    List<JoinPart> joins = mainSource.getJoins();
    if (joins == null || joins.isEmpty()) {
      return;
    }

    // 1. Collect initially used sources from main source, select, where, group_by, having
    Set<String> usedSources = new HashSet<>();
    addToUsedSources(usedSources, mainSource.getAlias());
    addToUsedSources(usedSources, mainSource.getTable());

    addFieldSources(usedSources, node.getSelect());
    addConditionFieldSources(usedSources, node.getWhereFields());
    addConditionFieldSources(usedSources, node.getGroupByFields());
    addConditionFieldSources(usedSources, node.getHavingFields());

    // 2. Right-to-left scan: mark used JOINs and expand usedSources from their condition_fields
    Set<String> keepAliases = new HashSet<>();
    Set<String> keepTables = new HashSet<>();

    for (int i = joins.size() - 1; i >= 0; i--) {
      JoinPart join = joins.get(i);
      DataSource src = join.getSource();
      if (src == null) {
        continue;
      }

      String alias = src.getAlias();
      String table = src.getTable();
      String subquery = (src.getSubquery() != null) ? src.getSubquery().toString() : null;
      boolean isInner = "inner".equalsIgnoreCase(join.getJoinType());

      boolean isUsed = isInner
        || (alias != null && usedSources.contains(alias))
        || (table != null && usedSources.contains(table))
        || (subquery != null && usedSources.contains(subquery));

      if (isUsed) {
        if (alias != null) keepAliases.add(alias);
        if (table != null) keepTables.add(table);
        if (subquery != null) keepTables.add(subquery);

        // Add condition field sources to usedSources
        FullParsJoinCondition jc = findJoinCondition(node.getJoinFields(), alias, table, subquery);
        if (jc != null) {
          addConditionFieldSources(usedSources, jc.getConditionFields());
        }
      }
    }

    // 3. Filter joins
    List<JoinPart> filteredJoins = new ArrayList<>();
    for (JoinPart join : joins) {
      DataSource src = join.getSource();
      if (src == null) {
        continue;
      }

      String alias = src.getAlias();
      String table = src.getTable();
      String subquery = (src.getSubquery() != null) ? src.getSubquery().toString() : null;
      boolean isInner = "inner".equalsIgnoreCase(join.getJoinType());

      boolean keep = isInner
        || (alias != null && keepAliases.contains(alias))
        || (table != null && keepTables.contains(table))
        || (subquery != null && keepTables.contains(subquery));

      if (keep) {
        // Recursively filter nested joins
        if (src.getJoins() != null && !src.getJoins().isEmpty()) {
          filterNestedJoins(src, usedSources);
        }
        filteredJoins.add(join);
      }
    }

    mainSource.setJoins(filteredJoins);

    // 4. Filter join_fields
    List<FullParsJoinCondition> filteredJoinFields = new ArrayList<>();
    for (FullParsJoinCondition jc : node.getJoinFields()) {
      String srcName = jc.getSource();
      if (srcName == null) {
        continue;
      }
      boolean keep = false;
      for (JoinPart join : filteredJoins) {
        DataSource js = join.getSource();
        if (js != null) {
          if (srcName.equals(js.getAlias())) keep = true;
          if (srcName.equals(js.getTable())) keep = true;
          if (js.getSubquery() != null && srcName.equals(js.getSubquery().toString())) keep = true;
        }
      }
      if (keep) {
        filteredJoinFields.add(jc);
      }
    }

    node.setJoinFields(filteredJoinFields);
  }

  private void filterNestedJoins(DataSource source, Set<String> usedSources) {
    List<JoinPart> joins = source.getJoins();
    if (joins == null || joins.isEmpty()) {
      return;
    }

    Set<String> keepAliases = new HashSet<>();
    Set<String> keepTables = new HashSet<>();

    for (int i = joins.size() - 1; i >= 0; i--) {
      JoinPart join = joins.get(i);
      DataSource src = join.getSource();
      if (src == null) {
        continue;
      }

      String alias = src.getAlias();
      String table = src.getTable();
      String subquery = (src.getSubquery() != null) ? src.getSubquery().toString() : null;
      boolean isInner = "inner".equalsIgnoreCase(join.getJoinType());

      boolean isUsed = isInner
        || (alias != null && usedSources.contains(alias))
        || (table != null && usedSources.contains(table))
        || (subquery != null && usedSources.contains(subquery));

      if (isUsed) {
        if (alias != null) keepAliases.add(alias);
        if (table != null) keepTables.add(table);
        if (subquery != null) keepTables.add(subquery);
      }
    }

    List<JoinPart> filteredJoins = new ArrayList<>();
    for (JoinPart join : joins) {
      DataSource src = join.getSource();
      if (src == null) {
        continue;
      }

      String alias = src.getAlias();
      String table = src.getTable();
      String subquery = (src.getSubquery() != null) ? src.getSubquery().toString() : null;
      boolean isInner = "inner".equalsIgnoreCase(join.getJoinType());

      boolean keep = isInner
        || (alias != null && keepAliases.contains(alias))
        || (table != null && keepTables.contains(table))
        || (subquery != null && keepTables.contains(subquery));

      if (keep) {
        if (src.getJoins() != null && !src.getJoins().isEmpty()) {
          filterNestedJoins(src, usedSources);
        }
        filteredJoins.add(join);
      }
    }

    source.setJoins(filteredJoins);
  }

  private void addToUsedSources(Set<String> usedSources, String value) {
    if (value != null) {
      usedSources.add(value);
    }
  }

  private void addFieldSources(Set<String> usedSources, List<FullParsSelectField> fields) {
    if (fields == null) return;
    for (FullParsSelectField sf : fields) {
      if (sf.getChildFields() != null) {
        for (FullParsChildField child : sf.getChildFields()) {
          addToUsedSources(usedSources, child.getNodeName());
          addToUsedSources(usedSources, child.getSource());
        }
      }
    }
  }

  private void addConditionFieldSources(Set<String> usedSources, List<FullParsConditionField> fields) {
    if (fields == null) return;
    for (FullParsConditionField cf : fields) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          addToUsedSources(usedSources, child.getNodeName());
          addToUsedSources(usedSources, child.getSource());
        }
      }
    }
  }

  private FullParsJoinCondition findJoinCondition(List<FullParsJoinCondition> joinFields,
                                                   String alias, String table, String subquery) {
    if (joinFields == null) return null;
    for (FullParsJoinCondition jc : joinFields) {
      String src = jc.getSource();
      if (src == null) continue;
      if (alias != null && src.equals(alias)) return jc;
      if (table != null && src.equals(table)) return jc;
      if (subquery != null && src.equals(subquery)) return jc;
    }
    return null;
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
