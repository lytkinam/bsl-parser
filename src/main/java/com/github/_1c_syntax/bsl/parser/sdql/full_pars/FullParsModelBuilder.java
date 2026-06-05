package com.github._1c_syntax.bsl.parser.sdql.full_pars;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.HierarchyNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsNode;
import com.github._1c_syntax.bsl.parser.sdql.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FullParsModelBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern FIELD_REF_PATTERN = Pattern.compile("(?U)([\\w]+)\\.([\\w]+)");
  private static final Pattern THREE_PART_PATTERN = Pattern.compile("(?U)([\\w]+)\\.([\\w]+)\\.([\\w]+)");
  private static final Pattern INLINE_SUB_PATTERN = Pattern.compile("(?U)[А-Яа-яA-Za-z_][А-Яа-яA-Za-z0-9_]*_(WHERE|HAVING|VT|SELECT|JOIN)_\\d+");

  private Map<Integer, LineParsNode> nodeById;
  private Map<String, LineParsNode> nodeByName;
  private Map<Integer, HierarchyNode> hierarchyById;
  private int fieldIdCounter = 1;
  private Map<String, Integer> fieldIdByNodeAndAlias = new HashMap<>();

  public Path build(Path lineParsDir, String baseName) throws IOException {
    LineParsModel lineParsModel = MAPPER.readValue(
      lineParsDir.resolve("LINE_PARS_model_" + baseName + ".json").toFile(),
      LineParsModel.class);

    List<HierarchyNode> hierarchy = MAPPER.readValue(
      lineParsDir.resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(),
      new TypeReference<List<HierarchyNode>>() {});

    this.nodeById = lineParsModel.getNodes().stream()
      .collect(Collectors.toMap(LineParsNode::getId, n -> n));
    this.nodeByName = lineParsModel.getNodes().stream()
      .collect(Collectors.toMap(LineParsNode::getName, n -> n, (a, b) -> a));
    this.hierarchyById = hierarchy.stream()
      .filter(h -> h.getId() != null)
      .collect(Collectors.toMap(HierarchyNode::getId, h -> h));

    // First pass: assign field_id to all select fields across all nodes
    for (LineParsNode lpNode : lineParsModel.getNodes()) {
      if (lpNode.getSelect() != null) {
        for (SelectField sf : lpNode.getSelect()) {
          if (sf.getAlias() != null) {
            String key = lpNode.getId() + ":" + sf.getAlias();
            fieldIdByNodeAndAlias.put(key, fieldIdCounter++);
          }
        }
      }
    }

    // Second pass: build FullPars nodes
    FullParsModel fullParsModel = new FullParsModel();
    for (LineParsNode lpNode : lineParsModel.getNodes()) {
      FullParsNode fpNode = convertNode(lpNode);
      fullParsModel.getNodes().add(fpNode);
    }

    Path fullParsDir = lineParsDir.getParent().resolve("FULL_PARS");
    Files.createDirectories(fullParsDir);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(
      fullParsDir.resolve("FULL_PARS_model_" + baseName + ".json").toFile(), fullParsModel);

    return fullParsDir;
  }

  private FullParsNode convertNode(LineParsNode lpNode) {
    FullParsNode fpNode = new FullParsNode();
    fpNode.setId(lpNode.getId());
    fpNode.setSdblId(lpNode.getSdblId());
    fpNode.setName(lpNode.getName());
    fpNode.setType(lpNode.getType());
    fpNode.setInto(lpNode.getInto());
    fpNode.setFrom(lpNode.getFrom());
    // WhereBlock
    if (lpNode.getWhere() != null) {
      WhereBlock wb = new WhereBlock();
      wb.setText(lpNode.getWhere().getText());
      wb.setSubqueryIds(lpNode.getWhere().getSubqueryIds());
      if (lpNode.getWhere().getText() != null) {
        wb.setFields(extractConditionFields(lpNode.getId(), lpNode.getWhere().getText()));
      }
      fpNode.setWhere(wb);
    }
    fpNode.setGroupBy(lpNode.getGroupBy());
    // HavingBlock
    if (lpNode.getHaving() != null) {
      HavingBlock hb = new HavingBlock();
      hb.setText(lpNode.getHaving().getText());
      hb.setSubqueryIds(lpNode.getHaving().getSubqueryIds());
      if (lpNode.getHaving().getText() != null) {
        hb.setFields(extractConditionFields(lpNode.getId(), lpNode.getHaving().getText()));
      }
      fpNode.setHaving(hb);
    }
    fpNode.setForUpdate(lpNode.getForUpdate());
    fpNode.setIndexBy(lpNode.getIndexBy());
    fpNode.setIndexBySets(lpNode.getIndexBySets());
    fpNode.setLimitations(lpNode.getLimitations());
    fpNode.setAutoorder(lpNode.getAutoorder());
    fpNode.setOrderBy(lpNode.getOrderBy());
    fpNode.setUpqueryId(lpNode.getUpqueryId());
    fpNode.setSubqueryIds(lpNode.getSubqueryIds());
    fpNode.setUnionNodesIds(lpNode.getUnionNodesIds());
    fpNode.setUnionGroupId(lpNode.getUnionGroupId());
    fpNode.setUnionType(lpNode.getUnionType());

    // Convert select fields
    if (lpNode.getSelect() != null) {
      List<FullParsSelectField> selectFields = new ArrayList<>();
      for (SelectField sf : lpNode.getSelect()) {
        FullParsSelectField f = new FullParsSelectField();
        f.setAlias(sf.getAlias());
        f.setText(sf.getText());
        if (sf.getAlias() != null) {
          String key = lpNode.getId() + ":" + sf.getAlias();
          f.setFieldId(fieldIdByNodeAndAlias.get(key));
        }
        f.setChildFields(extractChildFields(lpNode.getId(), sf.getText()));
        selectFields.add(f);
      }
      fpNode.setSelect(selectFields);
    }

    // group_by_fields
    if (lpNode.getGroupBy() != null) {
      for (String expr : lpNode.getGroupBy()) {
        FullParsConditionField cf = new FullParsConditionField();
        cf.setText(expr);
        cf.setChildFields(extractChildFields(lpNode.getId(), expr));
        fpNode.getGroupByFields().add(cf);
      }
    }

    // join_conditions
    if (lpNode.getFrom() != null) {
      for (DataSource ds : lpNode.getFrom()) {
        extractJoinConditions(fpNode, lpNode.getId(), ds);
      }
    }

    return fpNode;
  }

  private void extractJoinConditions(FullParsNode fpNode, int nodeId, DataSource ds) {
    if (ds.getJoins() != null) {
      for (JoinPart jp : ds.getJoins()) {
        FullParsJoinCondition jc = new FullParsJoinCondition();
        jc.setJoinType(jp.getJoinType());
        DataSource src = jp.getSource();
        if (src != null) {
          jc.setSource(src.getAlias() != null ? src.getAlias() :
            src.getTable() != null ? src.getTable() :
              src.getVirtualTable() != null ? src.getVirtualTable().getText() :
                src.getParameterTable() != null ? src.getParameterTable() :
                  src.getExternalDataSource() != null ? src.getExternalDataSource() : null);
        }
        jc.setCondition(jp.getCondition());
        if (jp.getCondition() != null) {
          jc.getConditionFields().addAll(extractConditionFields(nodeId, jp.getCondition()));
        }
        fpNode.getJoinFields().add(jc);
      }
    }
  }

  private List<FullParsConditionField> extractConditionFields(int nodeId, String text) {
    List<FullParsConditionField> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    Matcher m = FIELD_REF_PATTERN.matcher(text);
    while (m.find()) {
      seen.add(m.group(1) + "." + m.group(2));
    }
    // Remove false positives from chains a.b.c -> remove b.c
    Matcher three = THREE_PART_PATTERN.matcher(text);
    while (three.find()) {
      seen.remove(three.group(2) + "." + three.group(3));
    }
    for (String ref : seen) {
      int dot = ref.indexOf('.');
      String alias = ref.substring(0, dot);
      String field = ref.substring(dot + 1);
      FullParsConditionField cf = new FullParsConditionField();
      cf.setText(ref);
      cf.setChildFields(resolveChildField(nodeId, alias, field));
      result.add(cf);
    }
    return result;
  }

  private List<FullParsChildField> extractChildFields(int nodeId, String text) {
    if (text == null) {
      return new ArrayList<>();
    }
    Set<TableFieldRef> candidates = new LinkedHashSet<>();
    Matcher m = FIELD_REF_PATTERN.matcher(text);
    while (m.find()) {
      candidates.add(new TableFieldRef(m.group(1), m.group(2)));
    }
    Matcher three = THREE_PART_PATTERN.matcher(text);
    while (three.find()) {
      candidates.remove(new TableFieldRef(three.group(2), three.group(3)));
    }

    List<FullParsChildField> result = new ArrayList<>();
    for (TableFieldRef ref : candidates) {
      result.addAll(resolveChildField(nodeId, ref.aliasTable, ref.fieldName));
    }

    // Also check for inline subquery references in text
    Matcher inline = INLINE_SUB_PATTERN.matcher(text);
    while (inline.find()) {
      String inlineName = inline.group();
      LineParsNode inlineNode = nodeByName.get(inlineName);
      if (inlineNode != null) {
        FullParsChildField child = new FullParsChildField();
        child.setNodeId(inlineNode.getId());
        child.setNodeName(inlineNode.getName());
        child.setSource(inlineNode.getName());
        result.add(child);
      }
    }

    return result;
  }

  private List<FullParsChildField> resolveChildField(int nodeId, String alias, String field) {
    List<FullParsChildField> result = new ArrayList<>();
    HierarchyNode hNode = findHierarchyNode(nodeId, alias);
    if (hNode == null) {
      return result;
    }

    FullParsChildField child = new FullParsChildField();
    child.setAlias(field);

    if (hNode.getId() != null) {
      // Subquery or temp table
      child.setNodeId(hNode.getId());
      child.setNodeName(hNode.getSource() != null ? hNode.getSource() : hNode.getName());
      child.setSource(hNode.getSource() != null ? hNode.getSource() : hNode.getName());

      LineParsNode childNode = nodeById.get(hNode.getId());
      if (childNode != null && childNode.getSelect() != null) {
        for (SelectField sf : childNode.getSelect()) {
          if (field.equals(sf.getAlias())) {
            String key = childNode.getId() + ":" + sf.getAlias();
            child.setFieldId(fieldIdByNodeAndAlias.get(key));
            break;
          }
        }
      }
    } else {
      // Physical table (leaf)
      child.setNodeName(hNode.getName());
      child.setSource(hNode.getSource());
    }

    result.add(child);
    return result;
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
