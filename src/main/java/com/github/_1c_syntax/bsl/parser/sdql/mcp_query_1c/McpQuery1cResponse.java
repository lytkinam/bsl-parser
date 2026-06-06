package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * JSON-RPC response for MCP_QUERY_1C service.
 * Mirrors McpResponse from sdql.mcp package.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpQuery1cResponse {
  private String jsonrpc;
  private String id;
  private McpResult result;
  private McpError error;

  @Data
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class McpResult {
    private List<McpContent> content;
    private Boolean isError;

    public static McpResult text(String text) {
      McpResult r = new McpResult();
      r.content = List.of(new McpContent("text", text));
      return r;
    }
  }

  @Data
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class McpContent {
    private String type;
    private String text;

    public McpContent() {}

    public McpContent(String type, String text) {
      this.type = type;
      this.text = text;
    }
  }

  @Data
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class McpError {
    private int code;
    private String message;
    private Map<String, Object> data;
  }
}
