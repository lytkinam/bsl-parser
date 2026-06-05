package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelectField {
  @JsonIgnore
  private String fieldType;
  private String text;
  private String alias;
  private WhereSubquery inlineSubquery;
  private Integer inlineSubqueryId;
}
