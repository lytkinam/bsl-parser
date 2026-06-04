package com.github._1c_syntax.bsl.parser.sdql.full_pars;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class FullParsJoinCondition {
  @JsonProperty("join_type")
  private String joinType;
  private String source;
  private String condition;
  @JsonProperty("condition_fields")
  private List<FullParsConditionField> conditionFields = new ArrayList<>();
}
