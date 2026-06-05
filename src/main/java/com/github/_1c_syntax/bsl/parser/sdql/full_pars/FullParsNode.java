package com.github._1c_syntax.bsl.parser.sdql.full_pars;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.HavingBlock;
import com.github._1c_syntax.bsl.parser.sdql.model.WhereBlock;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class FullParsNode {
  private Integer id;

  @JsonProperty("sdbl_id")
  private Integer sdblId;

  private String name;
  private String type;
  private String into;

  private List<FullParsSelectField> select;
  private List<DataSource> from;
  private WhereBlock where;

  @JsonProperty("where_fields")
  private List<FullParsConditionField> whereFields = new ArrayList<>();

  @JsonProperty("group_by")
  private List<String> groupBy;

  @JsonProperty("group_by_fields")
  private List<FullParsConditionField> groupByFields = new ArrayList<>();

  private HavingBlock having;

  @JsonProperty("having_fields")
  private List<FullParsConditionField> havingFields = new ArrayList<>();

  private String forUpdate;

  @JsonProperty("index_by")
  private List<String> indexBy;

  @JsonProperty("index_by_sets")
  private List<String> indexBySets;

  private String limitations;
  private Boolean autoorder;

  @JsonProperty("order_by")
  private List<String> orderBy;

  @JsonProperty("join_fields")
  private List<FullParsJoinCondition> joinFields = new ArrayList<>();

  @JsonProperty("upquery_id")
  private Integer upqueryId;

  @JsonProperty("subquery_ids")
  private List<Integer> subqueryIds = new ArrayList<>();

  @JsonProperty("union_nodes_ids")
  private List<Integer> unionNodesIds = new ArrayList<>();

  @JsonProperty("union_group_id")
  private Integer unionGroupId;

  @JsonProperty("union_type")
  private String unionType;
}
