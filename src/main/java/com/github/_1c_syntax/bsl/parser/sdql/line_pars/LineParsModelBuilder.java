package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LineParsModelBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private int idCounter = 0;
  private int resultTableCount = 0;
  private List<LineParsNode> nodes = new ArrayList<>();
  private Map<Integer, Integer> subQueryCounters = new HashMap<>();
  private List<String> dropQueries = new ArrayList<>();

  public Path build(Path sdblParsDir, String baseName) throws IOException {
    QueryModel sdblModel = MAPPER.readValue(
      sdblParsDir.resolve("sdbl_parse_model_" + baseName + ".json").toFile(),
      QueryModel.class);

    for (QueryNode qn : sdblModel.getNodes()) {
      if ("drop_query".equals(qn.getType())) {
        if (qn.getName() != null) {
          dropQueries.add(qn.getName());
        }
        continue;
      }

      LineParsNode node = new LineParsNode();
      node.setId(idCounter++);
      node.setSdblId(qn.getId());
      node.setName(qn.getName() != null ? qn.getName() : "Результат_" + (++resultTableCount));
      node.setType(qn.getType() != null ? qn.getType() : "result");
      copyQueryFields(node, qn.getQuery());
      nodes.add(node);

      if (qn.getQuery() != null) {
        processAst(node, qn.getQuery());
      }
    }

    LineParsModel model = new LineParsModel();
    model.setNodes(nodes);
    model.setEdges(new ArrayList<>()); // reserved for next iteration
    model.setDropQueries(dropQueries);

    Path lineParsDir = sdblParsDir.getParent().resolve("LINE_PARS");
    Files.createDirectories(lineParsDir);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(
      lineParsDir.resolve("LINE_PARS_model_" + baseName + ".json").toFile(), model);
    return lineParsDir;
  }

  private void processAst(LineParsNode parent, QueryAst ast) {
    // Process unions FIRST (before subqueries modify the AST)
    if (ast.getUnions() != null && !ast.getUnions().isEmpty()) {
      List<SelectField> originalSelect = ast.getSelect();

      // Create UNION_0 from original query (copy before subquery modification)
      QueryAst union0Query = copyQueryAst(ast);
      union0Query.setInto(null);
      union0Query.setUnions(null);
      // Ensure select aliases are preserved
      if (originalSelect != null && union0Query.getSelect() != null) {
        List<SelectField> union0Select = union0Query.getSelect();
        for (int i = 0; i < Math.min(originalSelect.size(), union0Select.size()); i++) {
          union0Select.get(i).setFieldType("expression");
          if (originalSelect.get(i).getAlias() != null) {
            union0Select.get(i).setAlias(originalSelect.get(i).getAlias());
          }
        }
      }

      LineParsNode union0 = new LineParsNode();
      union0.setId(idCounter++);
      union0.setSdblId(parent.getSdblId());
      union0.setName(parent.getName() + "_UNION_0");
      union0.setType("union_query");
      union0.setUnionType("union_all");
      copyQueryFields(union0, union0Query);
      union0.setUnionGroupId(parent.getId());
      nodes.add(union0);
      parent.getUnionNodesIds().add(union0.getId());

      processAst(union0, union0Query);

      // Process UnionPart as UNION_1, UNION_2, ...
      int partCount = 0;
      for (UnionPart up : ast.getUnions()) {
        partCount++;
        LineParsNode part = new LineParsNode();
        part.setId(idCounter++);
        part.setSdblId(parent.getSdblId());
        part.setName(parent.getName() + "_UNION_" + partCount);
        part.setType("union_query");
        part.setUnionType(up.getUnionType());
        copyQueryFields(part, up.getQuery());
        part.setUnionGroupId(parent.getId());

        // Copy aliases from original select into union part's select
        if (originalSelect != null && up.getQuery() != null && up.getQuery().getSelect() != null) {
          List<SelectField> partSelect = up.getQuery().getSelect();
          for (int i = 0; i < Math.min(originalSelect.size(), partSelect.size()); i++) {
            if (originalSelect.get(i).getAlias() != null) {
              partSelect.get(i).setAlias(originalSelect.get(i).getAlias());
            }
          }
        }

        nodes.add(part);
        parent.getUnionNodesIds().add(part.getId());

        if (up.getQuery() != null) {
          processAst(part, up.getQuery());
        }
      }

      // Replace parent fields with virtual union fields
      parent.setInto(ast.getInto());
      List<SelectField> virtualSelect = new ArrayList<>();
      if (originalSelect != null) {
        for (SelectField sf : originalSelect) {
          SelectField vf = new SelectField();
          vf.setFieldType("union_field");
          vf.setText(sf.getAlias() != null ? sf.getAlias() : sf.getText());
          vf.setAlias(sf.getAlias());
          virtualSelect.add(vf);
        }
      }
      parent.setSelect(virtualSelect);
      parent.setFrom(null);
      parent.setWhere(null);
      parent.setGroupBy(null);
      parent.setGroupByGroupingSets(null);
      parent.setHaving(null);
      parent.setForUpdate(null);
      parent.setIndexBy(null);
      parent.setIndexBySets(null);
      parent.setLimitations(null);
      parent.setTotals(null);
      parent.setAutoorder(null);
    }

    // Process subqueries in FROM
    if (ast.getFrom() != null) {
      for (DataSource ds : ast.getFrom()) {
        processDataSource(parent, ds);
      }
    }

    // Process inline subqueries from WhereBlock
    if (ast.getWhere() != null && ast.getWhere().getSubqueries() != null) {
      int counter = 1;
      List<Integer> whereSubIds = new ArrayList<>();
      for (WhereSubquery ws : ast.getWhere().getSubqueries()) {
        LineParsNode sub = createInlineSubNode(parent, ws.getQuery(), parent.getName() + "_WHERE_" + counter++);
        whereSubIds.add(sub.getId());
      }
      // Replace temp names with sub node names in where text
      WhereBlock wb = new WhereBlock();
      wb.setText(replaceTempNames(ast.getWhere().getText(), ast.getWhere().getSubqueries(), parent.getName(), "WHERE"));
      wb.setSubqueryIds(whereSubIds);
      parent.setWhere(wb);
      parent.getSubqueryIds().addAll(whereSubIds);
    }

    // Process inline subqueries from HavingBlock
    if (ast.getHaving() != null && ast.getHaving().getSubqueries() != null) {
      int counter = 1;
      List<Integer> havingSubIds = new ArrayList<>();
      for (WhereSubquery ws : ast.getHaving().getSubqueries()) {
        LineParsNode sub = createInlineSubNode(parent, ws.getQuery(), parent.getName() + "_HAVING_" + counter++);
        havingSubIds.add(sub.getId());
      }
      HavingBlock hb = new HavingBlock();
      hb.setText(replaceTempNames(ast.getHaving().getText(), ast.getHaving().getSubqueries(), parent.getName(), "HAVING"));
      hb.setSubqueryIds(havingSubIds);
      parent.setHaving(hb);
      parent.getSubqueryIds().addAll(havingSubIds);
    }

    // Process inline subqueries from VirtualTableBlock
    if (ast.getFrom() != null) {
      for (DataSource ds : ast.getFrom()) {
        if (ds.getVirtualTable() != null && ds.getVirtualTable().getSubqueries() != null) {
          int counter = 1;
          List<Integer> vtSubIds = new ArrayList<>();
          for (WhereSubquery ws : ds.getVirtualTable().getSubqueries()) {
            LineParsNode sub = createInlineSubNode(parent, ws.getQuery(), parent.getName() + "_VT_" + counter++);
            vtSubIds.add(sub.getId());
          }
          VirtualTableBlock vt = new VirtualTableBlock();
          vt.setText(replaceTempNames(ds.getVirtualTable().getText(), ds.getVirtualTable().getSubqueries(), parent.getName(), "VT"));
          vt.setSubqueryIds(vtSubIds);
          ds.setVirtualTable(vt);
          parent.getSubqueryIds().addAll(vtSubIds);
        }
        if (ds.getJoins() != null) {
          for (JoinPart jp : ds.getJoins()) {
            if (jp.getConditionSubqueries() != null) {
              int counter = 1;
              List<Integer> joinSubIds = new ArrayList<>();
              for (WhereSubquery ws : jp.getConditionSubqueries()) {
                LineParsNode sub = createInlineSubNode(parent, ws.getQuery(), parent.getName() + "_JOIN_" + counter++);
                joinSubIds.add(sub.getId());
              }
              String replacedCond = replaceTempNames(jp.getCondition(), jp.getConditionSubqueries(), parent.getName(), "JOIN");
              jp.setCondition(replacedCond);
              jp.setConditionSubqueryIds(joinSubIds);
              jp.setConditionSubqueries(null);
              parent.getSubqueryIds().addAll(joinSubIds);
            }
          }
        }
      }
    }

    // Process inline subqueries from SelectField
    if (ast.getSelect() != null) {
      int counter = 1;
      for (SelectField sf : ast.getSelect()) {
        if (sf.getInlineSubquery() != null) {
          LineParsNode sub = createInlineSubNode(parent, sf.getInlineSubquery().getQuery(), parent.getName() + "_SELECT_" + counter++);
          sf.setInlineSubqueryId(sub.getId());
          sf.setInlineSubquery(null);
          parent.getSubqueryIds().add(sub.getId());
        }
      }
    }
  }

  private LineParsNode createInlineSubNode(LineParsNode parent, QueryAst query, String name) {
    LineParsNode sub = new LineParsNode();
    sub.setId(idCounter++);
    sub.setSdblId(parent.getSdblId());
    sub.setName(name);
    sub.setType("sub_query");
    copyQueryFields(sub, query);
    sub.setUpqueryId(parent.getId());
    nodes.add(sub);
    processAst(sub, query);
    return sub;
  }

  private String replaceTempNames(String text, List<WhereSubquery> subqueries, String parentName, String context) {
    if (text == null || subqueries == null) return text;
    String result = text;
    for (int i = 0; i < subqueries.size(); i++) {
      String tempName = subqueries.get(i).getName();
      String subName = parentName + "_" + context + "_" + (i + 1);
      result = result.replace(tempName, subName);
    }
    return result;
  }

  private void copyQueryFields(LineParsNode node, QueryAst ast) {
    if (ast == null) return;
    node.setInto(ast.getInto());
    node.setSelect(normalizeSelectFields(ast.getSelect()));
    node.setFrom(ast.getFrom());
    if (ast.getWhere() != null) {
      WhereBlock wb = new WhereBlock();
      wb.setText(ast.getWhere().getText());
      node.setWhere(wb);
    }
    node.setGroupBy(ast.getGroupBy());
    node.setGroupByGroupingSets(ast.getGroupByGroupingSets());
    if (ast.getHaving() != null) {
      HavingBlock hb = new HavingBlock();
      hb.setText(ast.getHaving().getText());
      node.setHaving(hb);
    }
    node.setForUpdate(ast.getForUpdate());
    node.setIndexBy(ast.getIndexBy());
    node.setIndexBySets(ast.getIndexBySets());
    node.setLimitations(ast.getLimitations());
    node.setAutoorder(ast.getAutoorder());
    node.setOrderBy(ast.getOrderBy());
    node.setTotals(ast.getTotals());
  }

  private List<SelectField> normalizeSelectFields(List<SelectField> select) {
    if (select == null) return null;
    for (SelectField sf : select) {
      if (sf.getAlias() == null && sf.getText() != null) {
        sf.setAlias(sanitizeAlias(sf.getText()));
      }
    }
    return select;
  }

  private String sanitizeAlias(String text) {
    if (text == null) return "";
    return text.replaceAll("[^\\p{L}\\p{N}]", "");
  }

  private QueryAst copyQueryAst(QueryAst source) {
    try {
      return MAPPER.readValue(MAPPER.writeValueAsString(source), QueryAst.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to copy QueryAst", e);
    }
  }

  private void processDataSource(LineParsNode parent, DataSource ds) {
    if (ds.getSubquery() != null) {
      int count = subQueryCounters.getOrDefault(parent.getId(), 0) + 1;
      subQueryCounters.put(parent.getId(), count);
      String subName = parent.getName() + "_SUB_" + count;

      QueryAst subqueryAst = (QueryAst) ds.getSubquery();

      LineParsNode sub = new LineParsNode();
      sub.setId(idCounter++);
      sub.setSdblId(parent.getSdblId());
      sub.setName(subName);
      sub.setType("sub_query");
      copyQueryFields(sub, subqueryAst);
      sub.setUpqueryId(parent.getId());
      nodes.add(sub);
      parent.getSubqueryIds().add(sub.getId());

      // Replace subquery object with name string in parent query
      ds.setSubquery(subName);

      processAst(sub, subqueryAst);
    }

    if (ds.getJoins() != null) {
      for (JoinPart jp : ds.getJoins()) {
        DataSource src = jp.getSource();
        if (src != null) {
          processDataSource(parent, src);
        }
      }
    }
  }
}
