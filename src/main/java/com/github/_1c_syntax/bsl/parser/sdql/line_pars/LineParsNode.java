package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github._1c_syntax.bsl.parser.sdql.model.DataSource;
import com.github._1c_syntax.bsl.parser.sdql.model.HavingBlock;
import com.github._1c_syntax.bsl.parser.sdql.model.SelectField;
import com.github._1c_syntax.bsl.parser.sdql.model.TotalBy;
import com.github._1c_syntax.bsl.parser.sdql.model.WhereBlock;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class LineParsNode {
  private int id;
  private int sdblId;
  private String name;
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
  private Boolean autoorder;
  private List<String> orderBy;
  private TotalBy totals;
  private Integer upqueryId;
  private List<Integer> subqueryIds = new ArrayList<>();
  private List<Integer> unionNodesIds = new ArrayList<>();
  private Integer unionGroupId;
  private String unionType;
}
