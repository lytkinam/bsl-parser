package com.github._1c_syntax.bsl.parser.sdql.query_reconstruction;

import lombok.Data;

@Data
public class RestoredJoin {
  private String joinType;
  private String sourceTable;
  private String alias;
  private String condition;
}
