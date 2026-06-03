package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import lombok.Data;

import java.util.List;

@Data
public class LineParsModel {
  private List<LineParsNode> nodes;
  private List<LineParsEdge> edges;
  private List<String> dropQueries;
}
