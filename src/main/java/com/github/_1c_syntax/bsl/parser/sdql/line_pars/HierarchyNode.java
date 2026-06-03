package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class HierarchyNode {
  private Integer id;
  private String name;
  private String source;

  @JsonProperty("type_hierarchy")
  private String typeHierarchy;

  @JsonProperty("table_hierarchy")
  private List<HierarchyNode> tableHierarchy = new ArrayList<>();
}
