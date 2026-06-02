package com.github._1c_syntax.bsl.parser.sdql.model;

import lombok.Data;

@Data
public class TableAlias {
  private String alias;
  private String primaryTable;
  private boolean isVirtual;
  private String joinType;
}
