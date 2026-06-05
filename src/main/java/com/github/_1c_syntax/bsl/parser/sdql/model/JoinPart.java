package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JoinPart {
  private String joinType;
  private DataSource source;
  private String condition;
  private List<WhereSubquery> conditionSubqueries;
  private List<Integer> conditionSubqueryIds;
}
