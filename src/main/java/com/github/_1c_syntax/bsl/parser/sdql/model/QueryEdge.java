package com.github._1c_syntax.bsl.parser.sdql.model;

import lombok.Data;

@Data
public class QueryEdge {
  private int from;
  private int to;
  private String fromName;
  private String toName;
}
