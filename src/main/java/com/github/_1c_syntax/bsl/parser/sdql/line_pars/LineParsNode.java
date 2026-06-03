package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryAst;
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
  private QueryAst query;
  private Integer upqueryId;
  private List<Integer> subqueryIds = new ArrayList<>();
  private List<Integer> unionNodesIds = new ArrayList<>();
  private Integer unionGroupId;
  private String unionType;
}
