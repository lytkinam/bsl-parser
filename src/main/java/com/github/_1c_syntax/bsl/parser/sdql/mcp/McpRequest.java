package com.github._1c_syntax.bsl.parser.sdql.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class McpRequest {
  private String jsonrpc;
  private String id;
  private String method;
  private McpParams params;

  @Data
  public static class McpParams {
    private String name;
    private JsonNode arguments;
  }
}
