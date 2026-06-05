package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HavingBlock {
  private String text;
  private List<WhereSubquery> subqueries;
  private List<Integer> subqueryIds;
  private List<com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsConditionField> fields;
}
