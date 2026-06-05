package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSource {
  private String table;
  private VirtualTableBlock virtualTable;
  private String parameterTable;
  private String externalDataSource;
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = SubqueryDeserializer.class)
  private Object subquery;
  private String alias;
  private List<JoinPart> joins;
}
