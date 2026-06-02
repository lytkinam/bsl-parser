package com.github._1c_syntax.bsl.parser.sdql.model;

import lombok.Data;

@Data
public class FieldRef {
  private String aliasTable;
  private String field;
  private String primaryTable;
}
