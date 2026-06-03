package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import lombok.Data;

@Data
public class LineParsEdge {
  private int from;
  private String fromName;
  private int to;
  private String toName;
}
