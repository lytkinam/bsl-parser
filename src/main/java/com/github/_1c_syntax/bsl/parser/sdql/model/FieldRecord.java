package com.github._1c_syntax.bsl.parser.sdql.model;

import lombok.Data;

import java.util.List;

@Data
public class FieldRecord {
  private String alias;
  private String expressionRaw;
  private String exprType;
  private List<FieldRef> fieldRefs;
}
