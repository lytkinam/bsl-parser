package com.github._1c_syntax.bsl.parser.sdql.full_pars;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class FullParsConditionField {
  private String text;
  @JsonProperty("child_fields")
  private List<FullParsChildField> childFields = new ArrayList<>();
}
