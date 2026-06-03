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

  public void build(Path sdblParsDir, String baseName) throws IOException {
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
      node.setQuery(qn.getQuery());
      nodes.add(node);

      if (qn.getQuery() != null) {
        processAst(node, qn.getQuery());
      }
    }

    // Clear startIndex/endIndex from all queries — not needed in LINE_PARS
    for (LineParsNode n : nodes) {
      if (n.getQuery() != null) {
        n.getQuery().setStartIndex(null);
        n.getQuery().setEndIndex(null);
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
  }

  private void processAst(LineParsNode parent, QueryAst ast) {
    // Process unions FIRST (before subqueries modify the AST)
    if (ast.getUnions() != null && !ast.getUnions().isEmpty()) {
      QueryAst originalQuery = parent.getQuery();
      List<SelectField> originalSelect = originalQuery.getSelect();

      // Create UNION_0 from original query (copy before subquery modification)
      QueryAst union0Query = copyQueryAst(originalQuery);
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
      union0.setQuery(union0Query);
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
        part.setQuery(up.getQuery());
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

      // Replace parent query with virtual union fields (generate from scratch)
      QueryAst virtualQuery = new QueryAst();
      virtualQuery.setType(originalQuery.getType());
      virtualQuery.setInto(originalQuery.getInto());
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
      virtualQuery.setSelect(virtualSelect);
      parent.setQuery(virtualQuery);
    }

    // Process subqueries in FROM
    if (ast.getFrom() != null) {
      for (DataSource ds : ast.getFrom()) {
        processDataSource(parent, ds);
      }
    }
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
      sub.setQuery(subqueryAst);
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
