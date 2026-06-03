package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LineParsModelBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private int idCounter = 0;
  private int unnamedSubQueryCount = 0;
  private int resultTableCount = 0;
  private List<LineParsNode> nodes = new ArrayList<>();
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
    // Process subqueries in FROM
    if (ast.getFrom() != null) {
      for (DataSource ds : ast.getFrom()) {
        processDataSource(parent, ds);
      }
    }

    // Process unions
    if (ast.getUnions() != null && !ast.getUnions().isEmpty()) {
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
        // Alias from parent's first select field
        if (parent.getQuery() != null && parent.getQuery().getSelect() != null
            && !parent.getQuery().getSelect().isEmpty()) {
          String firstAlias = parent.getQuery().getSelect().get(0).getAlias();
          if (firstAlias != null) {
            part.setAlias(firstAlias);
          }
        }
        // unionFirst on subsequent union nodes points to the first one
        if (partCount > 1) {
          part.setUnionFirst(parent.getUnionNodesIds().get(0));
        }
        nodes.add(part);
        parent.getUnionNodesIds().add(part.getId());

        if (up.getQuery() != null) {
          processAst(part, up.getQuery());
        }
      }
      // Remove unions from parent query — they are now separate nodes
      ast.setUnions(null);
    }
  }

  private void processDataSource(LineParsNode parent, DataSource ds) {
    if (ds.getSubquery() != null) {
      unnamedSubQueryCount++;
      String alias = ds.getAlias() != null ? ds.getAlias() : "Подзапрос_" + unnamedSubQueryCount;

      LineParsNode sub = new LineParsNode();
      sub.setId(idCounter++);
      sub.setSdblId(parent.getSdblId());
      sub.setName(alias);
      sub.setType("sub_query");
      sub.setQuery(ds.getSubquery());
      sub.setUpqueryId(parent.getId());
      nodes.add(sub);
      parent.getSubqueryIds().add(sub.getId());

      processAst(sub, ds.getSubquery());
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
