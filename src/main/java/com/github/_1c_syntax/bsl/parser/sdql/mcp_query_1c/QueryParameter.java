package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import lombok.Data;

import java.time.Instant;

/**
 * DTO representing a cached 1C query parameter.
 */
@Data
public class QueryParameter {

  private String name;
  private String sanitizedName;
  private String sqlText;
  private String baseName;
  private Instant updatedAt;
  private Source source;
  private int nodesCount;

  public enum Source {
    ONEC,   // Fetched from 1C MCP server
    CUSTOM  // Added manually via add_custom_query
  }
}
