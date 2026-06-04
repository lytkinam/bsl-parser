package com.github._1c_syntax.bsl.parser.sdql.full_pars;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FullParsChildField {
  private Integer fieldId;
  private String alias;
  private Integer nodeId;
  private String nodeName;
  private String source;
}
