package com.github._1c_syntax.bsl.parser.sdql.model;

import lombok.Data;

import java.util.List;

@Data
public class QueryModel {
  private List<QueryNode> nodes;
  private List<QueryEdge> edges;
  private String sourceHash;
  private int sourceLength;
}
