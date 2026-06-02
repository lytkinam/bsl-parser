package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryNode {
  private int id;
  private String type;
  private String name;
  private String textHash;
  private int textLength;
  private String text;
  private QueryAst query;
}
