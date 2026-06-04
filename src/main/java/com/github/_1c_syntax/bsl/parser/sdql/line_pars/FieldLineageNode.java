package com.github._1c_syntax.bsl.parser.sdql.line_pars;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class FieldLineageNode {
  private Integer id;
  private String name;
  private String alias;
  private String text;
  private String source;
  private String childName;

  @JsonProperty("child_fields")
  private List<FieldLineageNode> childFields = new ArrayList<>();
}
