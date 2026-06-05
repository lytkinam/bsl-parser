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
    buildLineage(target.getNodeId(), new ArrayList<>(target.getAliases()), resultMap, target.isNoRecurse());

    List<FullParsNode> resultList = new ArrayList<>(resultMap.values());
    // Sort by sdbl_id to ensure temp tables are defined before they are used
    resultList.sort(Comparator.comparingInt(FullParsNode::getSdblId));
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

  /**
   * Debug entry point: build FFL for a specific node with specific aliases.
   * Prints debug info to stderr.
   */
  public List<FullParsNode> buildLineage_debug(int nodeId, List<String> aliases, boolean noRecurse) {
    LinkedHashMap<Integer, FullParsNode> resultMap = new LinkedHashMap<>();
    System.err.println("[FFL-DEBUG] ====== buildLineage_debug START nodeId=" + nodeId + " aliases=" + aliases + " noRecurse=" + noRecurse + " ======");
    buildLineage(nodeId, new ArrayList<>(aliases), resultMap, noRecurse);
    List<FullParsNode> resultList = new ArrayList<>(resultMap.values());
    resultList.sort(Comparator.comparingInt(FullParsNode::getSdblId));
    System.err.println("[FFL-DEBUG] ====== buildLineage_debug END resultNodes=" + resultList.size() + " ======");
    for (FullParsNode n : resultList) {
      System.err.println("[FFL-DEBUG]   result node: id=" + n.getId() + " name=" + n.getName() + " type=" + n.getType() + " selectCount=" + (n.getSelect() != null ? n.getSelect().size() : 0));
    }
    return resultList;
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
    buildLineage(nodeId, aliases, resultMap, false);
  }

  private void buildLineage(int nodeId, List<String> aliases,
                            LinkedHashMap<Integer, FullParsNode> resultMap, boolean noRecurse) {
    FullParsNode node = nodeById.get(nodeId);
    if (node == null) {
      System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " NOT FOUND in nodeById");
      return;
    }

    System.err.println("[FFL-DEBUG] buildLineage START nodeId=" + nodeId + " name=" + node.getName() + " type=" + node.getType() + " aliases=" + aliases + " resultMap.has=" + resultMap.containsKey(nodeId) + " noRecurse=" + noRecurse);

    FullParsNode result;
    boolean isNewNode = !resultMap.containsKey(nodeId);
    List<String> newAliases = new ArrayList<>();
    if (!isNewNode) {
      result = resultMap.get(nodeId);
      System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " ALREADY in resultMap, extending select");
      // Extend select with new aliases
      for (String alias : aliases) {
        if (!hasAliasInSelect(result, alias)) {
          FullParsSelectField field = findFieldInNode(node, alias);
          if (field != null) {
            result.getSelect().add(cloneField(field));
            newAliases.add(alias);
            System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " extended select with alias=" + alias);
          }
        }
      }
      // Re-run filterUnusedJoins because select changed — may need more JOINs
      if (!newAliases.isEmpty() && result.getFrom() != null && !result.getFrom().isEmpty()) {
        System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " re-cloning from and re-running filterUnusedJoins");
        result.setFrom(cloneDataSources(node.getFrom()));
        result.setJoinFields(cloneJoinConditions(node.getJoinFields()));
        filterUnusedJoins(result);
        System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " after re-filter joins count=" + result.getFrom().get(0).getJoins().size());
      }
      System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " newAliases=" + newAliases);
    } else {
      result = createNode(node, aliases);
      resultMap.put(nodeId, result);
      newAliases.addAll(aliases);
      System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " CREATED new node, put in resultMap, selectCount=" + (result.getSelect() != null ? result.getSelect().size() : 0));
    }

    // If noRecurse, stop here — don't process children or unions
    if (noRecurse) {
      System.err.println("[FFL-DEBUG] buildLineage END nodeId=" + nodeId + " (noRecurse=true)");
      return;
    }

    // Handle UNION virtual parent
    if (node.getUnionNodesIds() != null && !node.getUnionNodesIds().isEmpty()) {
      System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " is UNION parent, unionIds=" + node.getUnionNodesIds() + " aliasesToPass=" + (isNewNode ? aliases : newAliases));
      // Pass new aliases to union parts so they can extend their select
      List<String> aliasesToPass = isNewNode ? aliases : newAliases;
      for (int unionId : node.getUnionNodesIds()) {
        buildLineage(unionId, aliasesToPass, resultMap, false);
      }
      System.err.println("[FFL-DEBUG] buildLineage END nodeId=" + nodeId + " (UNION parent)");
      return;
    }

    // Collect child_fields from this node's fields only (select + where + group_by + having)
    // Used to determine which LEFT JOINs are used in this node
    List<FullParsChildField> thisNodeFullChildFields = new ArrayList<>();

    for (FullParsSelectField sf : result.getSelect()) {
      if (sf.getChildFields() != null) {
        for (FullParsChildField child : sf.getChildFields()) {
          if (child.getNodeId() != null) {
            thisNodeFullChildFields.add(child);
          }
        }
      }
    }
    for (FullParsConditionField cf : result.getWhereFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            thisNodeFullChildFields.add(child);
          }
        }
      }
    }
    for (FullParsConditionField cf : result.getGroupByFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            thisNodeFullChildFields.add(child);
          }
        }
      }
    }
    for (FullParsConditionField cf : result.getHavingFields()) {
      if (cf.getChildFields() != null) {
        for (FullParsChildField child : cf.getChildFields()) {
          if (child.getNodeId() != null) {
            thisNodeFullChildFields.add(child);
          }
        }
      }
    }

    // Collect all child_fields including join_fields for recursion/FFL
    List<FullParsChildField> fullChildFields = new ArrayList<>(thisNodeFullChildFields);
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

    System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " thisNodeFullChildFields=" + thisNodeFullChildFields.size() + " fullChildFields=" + fullChildFields.size() + " subqueryIds=" + subqueryIds);
    for (FullParsChildField c : thisNodeFullChildFields) {
      System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " thisNodeChild: nodeId=" + c.getNodeId() + " alias=" + c.getAlias() + " nodeName=" + c.getNodeName());
    }

    // Group by node_id
    Map<Integer, List<FullParsChildField>> grouped = fullChildFields.stream()
      .collect(Collectors.groupingBy(FullParsChildField::getNodeId, LinkedHashMap::new, Collectors.toList()));

    System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " grouped children=" + grouped.size());

    // Recursive calls for child_fields
    for (Map.Entry<Integer, List<FullParsChildField>> entry : grouped.entrySet()) {
      int usedNodeId = entry.getKey();
      List<String> usedAliases = entry.getValue().stream()
        .map(FullParsChildField::getAlias)
        .distinct()
        .collect(Collectors.toList());
      System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " -> recurse to nodeId=" + usedNodeId + " aliases=" + usedAliases);
      buildLineage(usedNodeId, usedAliases, resultMap, false);
    }

    // Recursive calls for subqueries (inline subqueries in WHERE/HAVING/VirtualTable)
    for (int sqId : subqueryIds) {
      System.err.println("[FFL-DEBUG] buildLineage: nodeId=" + nodeId + " -> recurse to subquery nodeId=" + sqId);
      buildLineage(sqId, List.of(), resultMap, false);
    }

    System.err.println("[FFL-DEBUG] buildLineage END nodeId=" + nodeId + " name=" + node.getName());
  }

  private FullParsNode createNode(FullParsNode source, List<String> aliases) {
    FullParsNode result = new FullParsNode();
    result.setId(source.getId());
    result.setSdblId(source.getSdblId());
    result.setName(source.getName());
    result.setType(source.getType());
    result.setInto(source.getInto());
    result.setFrom(cloneDataSources(source.getFrom()));
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
    // If aliases is empty (e.g., sub_query reached via subquery_ids), copy all select fields
    // so that child_fields dependencies are visible for recursion
    System.err.println("[FFL-DEBUG] createNode START nodeId=" + source.getId() + " name=" + source.getName() + " aliases=" + aliases);
    List<FullParsSelectField> selectFields = new ArrayList<>();
    if (aliases.isEmpty() && source.getSelect() != null) {
      System.err.println("[FFL-DEBUG] createNode: nodeId=" + source.getId() + " aliases empty, copying ALL select fields");
      for (FullParsSelectField f : source.getSelect()) {
        selectFields.add(cloneField(f));
      }
    } else {
      for (String alias : aliases) {
        FullParsSelectField f = findFieldInNode(source, alias);
        System.err.println("[FFL-DEBUG] createNode: nodeId=" + source.getId() + " findFieldInNode alias=" + alias + " found=" + (f != null));
        if (f != null) {
          selectFields.add(cloneField(f));
        }
      }
    }
    System.err.println("[FFL-DEBUG] createNode: nodeId=" + source.getId() + " selectFields count=" + selectFields.size());

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
   * Collect sources (alias/table/subquery) of used JOINs for the node.
   * A LEFT JOIN is unused if neither its alias nor its table appear in
   * SELECT / WHERE / GROUP BY / HAVING fields, nor in condition_fields of
   * subsequent (to the right) used JOINs.
   *
   * INNER JOINs are always considered used — they filter rows.
   *
   * This is used in buildLineage() to skip child_fields from unused LEFT JOINs
   * when collecting fullChildFields for recursion. The node itself is NOT modified.
   */
  private Set<String> collectUsedJoinSources(FullParsNode node) {
    Set<String> usedJoinSources = new HashSet<>();
    if (node.getFrom() == null || node.getFrom().isEmpty()) {
      return usedJoinSources;
    }

    DataSource mainSource = node.getFrom().get(0);
    List<JoinPart> joins = mainSource.getJoins();
    if (joins == null || joins.isEmpty()) {
      return usedJoinSources;
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
        if (alias != null) usedJoinSources.add(alias);
        if (table != null) usedJoinSources.add(table);
        if (subquery != null) usedJoinSources.add(subquery);

        // Add condition field sources to usedSources for cascading
        FullParsJoinCondition jc = findJoinCondition(node.getJoinFields(), alias, table, subquery);
        if (jc != null) {
          addConditionFieldSources(usedSources, jc.getConditionFields());
        }
      }
    }

    return usedJoinSources;
  }

  /**
   * Removes unused LEFT JOINs from the node's from[].joins and join_fields.
   * A LEFT JOIN is unused if its source (alias/table/subquery) does not appear
   * in SELECT / WHERE / GROUP BY / HAVING fields of this node, nor in
   * condition_fields of subsequent (to the right) used JOINs.
   *
   * INNER JOINs are always kept — they filter rows.
   *
   * The node is modified in place: joins and join_fields are filtered.
   * Child nodes from join conditions are still collected for FFL recursion
   * separately in buildLineage().
   */
  private void filterUnusedJoins(FullParsNode node) {
    System.err.println("[FFL-DEBUG] filterUnusedJoins START nodeId=" + node.getId() + " name=" + node.getName() + " type=" + node.getType());
    if (node.getFrom() == null || node.getFrom().isEmpty()) {
      System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " NO from, returning");
      return;
    }

    DataSource mainSource = node.getFrom().get(0);
    List<JoinPart> joins = mainSource.getJoins();
    if (joins == null || joins.isEmpty()) {
      System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " NO joins, returning");
      return;
    }

    System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " joinsCount=" + joins.size());
    for (int i = 0; i < joins.size(); i++) {
      JoinPart j = joins.get(i);
      System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " join[" + i + "] type=" + j.getJoinType() + " alias=" + (j.getSource() != null ? j.getSource().getAlias() : null) + " table=" + (j.getSource() != null ? j.getSource().getTable() : null));
    }

    // 1. Collect initially used sources from main source, select, where, group_by, having
    Set<String> usedSources = new HashSet<>();
    addToUsedSources(usedSources, mainSource.getAlias());
    addToUsedSources(usedSources, mainSource.getTable());

    System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " selectCount=" + (node.getSelect() != null ? node.getSelect().size() : 0));
    if (node.getSelect() != null) {
      for (FullParsSelectField sf : node.getSelect()) {
        System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " select alias=" + sf.getAlias() + " text=" + sf.getText() + " childFields=" + (sf.getChildFields() != null ? sf.getChildFields().size() : 0));
        if (sf.getChildFields() != null) {
          for (FullParsChildField cf : sf.getChildFields()) {
            System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + "   child: nodeName=" + cf.getNodeName() + " source=" + cf.getSource());
          }
        }
      }
    }

    addFieldSources(usedSources, node.getSelect());
    addConditionFieldSources(usedSources, node.getWhereFields());
    addConditionFieldSources(usedSources, node.getGroupByFields());
    addConditionFieldSources(usedSources, node.getHavingFields());

    System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " usedSources after fields=" + usedSources);

    // 2. Right-to-left scan: determine which JOINs are used
    Set<String> usedJoinSources = new HashSet<>();
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

      System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " scan join[" + i + "] alias=" + alias + " table=" + table + " isInner=" + isInner + " isUsed=" + isUsed + " usedSources.hasAlias=" + (alias != null ? usedSources.contains(alias) : false));

      if (isUsed) {
        if (alias != null) usedJoinSources.add(alias);
        if (table != null) usedJoinSources.add(table);
        if (subquery != null) usedJoinSources.add(subquery);

        // Add condition field sources to usedSources for cascading
        FullParsJoinCondition jc = findJoinCondition(node.getJoinFields(), alias, table, subquery);
        if (jc != null) {
          addConditionFieldSources(usedSources, jc.getConditionFields());
        }
      }
    }

    System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " usedJoinSources=" + usedJoinSources);

    // 3. Filter joins: keep only used ones (preserve original order)
    List<JoinPart> filteredJoins = new ArrayList<>();
    for (JoinPart join : joins) {
      DataSource src = join.getSource();
      if (src == null) {
        filteredJoins.add(join);
        continue;
      }
      String alias = src.getAlias();
      String table = src.getTable();
      String subquery = (src.getSubquery() != null) ? src.getSubquery().toString() : null;

      boolean isUsed = "inner".equalsIgnoreCase(join.getJoinType())
        || (alias != null && usedJoinSources.contains(alias))
        || (table != null && usedJoinSources.contains(table))
        || (subquery != null && usedJoinSources.contains(subquery));

      System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " filter alias=" + alias + " isUsed=" + isUsed);

      if (isUsed) {
        filteredJoins.add(join);
      }
    }
    mainSource.setJoins(filteredJoins);

    System.err.println("[FFL-DEBUG] filterUnusedJoins: nodeId=" + node.getId() + " filteredJoins size=" + filteredJoins.size());

    // 4. Filter join_fields: keep only for used joins
    if (node.getJoinFields() != null) {
      List<FullParsJoinCondition> filteredJoinFields = new ArrayList<>();
      for (FullParsJoinCondition jc : node.getJoinFields()) {
        String src = jc.getSource();
        if (src == null) {
          filteredJoinFields.add(jc);
          continue;
        }
        if (usedJoinSources.contains(src)) {
          filteredJoinFields.add(jc);
        }
      }
      node.setJoinFields(filteredJoinFields);
    }

    System.err.println("[FFL-DEBUG] filterUnusedJoins END nodeId=" + node.getId());
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

  private List<DataSource> cloneDataSources(List<DataSource> source) {
    if (source == null) return null;
    return source.stream().map(this::cloneDataSource).collect(Collectors.toList());
  }

  private DataSource cloneDataSource(DataSource source) {
    if (source == null) return null;
    DataSource ds = new DataSource();
    ds.setTable(source.getTable());
    ds.setAlias(source.getAlias());
    ds.setSubquery(source.getSubquery());
    ds.setParameterTable(source.getParameterTable());
    ds.setExternalDataSource(source.getExternalDataSource());
    ds.setVirtualTable(source.getVirtualTable());
    if (source.getJoins() != null) {
      ds.setJoins(source.getJoins().stream().map(this::cloneJoinPart).collect(Collectors.toList()));
    }
    return ds;
  }

  private JoinPart cloneJoinPart(JoinPart source) {
    if (source == null) return null;
    JoinPart jp = new JoinPart();
    jp.setJoinType(source.getJoinType());
    jp.setSource(cloneDataSource(source.getSource()));
    jp.setCondition(source.getCondition());
    jp.setConditionSubqueries(source.getConditionSubqueries());
    jp.setConditionSubqueryIds(source.getConditionSubqueryIds());
    return jp;
  }

  public static class TargetField {
    private final int nodeId;
    private final String nodeName;
    private final List<String> aliases;
    private final boolean noRecurse;

    public TargetField(int nodeId, String nodeName, List<String> aliases) {
      this(nodeId, nodeName, aliases, false);
    }

    public TargetField(int nodeId, String nodeName, List<String> aliases, boolean noRecurse) {
      this.nodeId = nodeId;
      this.nodeName = nodeName;
      this.aliases = aliases;
      this.noRecurse = noRecurse;
    }

    public int getNodeId() { return nodeId; }
    public String getNodeName() { return nodeName; }
    public List<String> getAliases() { return aliases; }
    public boolean isNoRecurse() { return noRecurse; }
  }
}
