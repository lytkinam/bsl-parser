package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryAst {
  private Integer startIndex;
  private Integer endIndex;
  private String type;
  private String into;
  private List<SelectField> select;
  private List<DataSource> from;
  private WhereBlock where;
  private List<String> groupBy;
  private List<List<String>> groupByGroupingSets;
  private HavingBlock having;
  private String forUpdate;
  private List<String> indexBy;
  private List<String> indexBySets;
  private String limitations;
  private List<UnionPart> unions;
  private Boolean autoorder;
  private List<String> orderBy;
  private TotalBy totals;
}
