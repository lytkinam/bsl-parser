package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.IOException;

/**
 * HTTP server for MCP_QUERY_1C service.
 * Handles JSON-RPC MCP requests on /mcp endpoint.
 */
public class McpQuery1cHttpServer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Server server;
  private final McpQuery1cTools mcpTools;

  public McpQuery1cHttpServer(int port, McpQuery1cTools mcpTools) {
    this.mcpTools = mcpTools;

    QueuedThreadPool threadPool = new QueuedThreadPool();
    threadPool.setName("mcp-query-1c");
    this.server = new Server(threadPool);

    ServerConnector connector = new ServerConnector(server);
    connector.setPort(port);
    server.addConnector(connector);

    server.setHandler(new AbstractHandler() {
      @Override
      public void handle(String target, org.eclipse.jetty.server.Request baseRequest,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        baseRequest.setHandled(true);
        response.setContentType("application/json; charset=UTF-8");

        if ("/health".equals(target) && "GET".equalsIgnoreCase(request.getMethod())) {
          response.setStatus(HttpServletResponse.SC_OK);
          response.getWriter().write("{\"status\":\"ok\"}");
          return;
        }

        if (!"/mcp".equals(target) || !"POST".equalsIgnoreCase(request.getMethod())) {
          response.setStatus(HttpServletResponse.SC_NOT_FOUND);
          response.getWriter().write("{\"error\":\"Not found\"}");
          return;
        }

        try {
          JsonNode jsonRequest = MAPPER.readTree(request.getInputStream());
          String requestId = jsonRequest.has("id") ? jsonRequest.get("id").asText() : "null";
          String method = jsonRequest.has("method") ? jsonRequest.get("method").asText() : "";

          if (!"tools/call".equals(method) && !"tools/list".equals(method)) {
            McpQuery1cResponse errorResp = new McpQuery1cResponse();
            errorResp.setJsonrpc("2.0");
            errorResp.setId(requestId);
            McpQuery1cResponse.McpError error = new McpQuery1cResponse.McpError();
            error.setCode(-32601);
            error.setMessage("Method not found: " + method);
            errorResp.setError(error);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(MAPPER.writeValueAsString(errorResp));
            return;
          }

          if ("tools/list".equals(method)) {
            McpQuery1cResponse listResp = buildToolsListResponse(requestId);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(MAPPER.writeValueAsString(listResp));
            return;
          }

          JsonNode params = jsonRequest.has("params") ? jsonRequest.get("params") : null;
          String toolName = params != null && params.has("name") ? params.get("name").asText() : "";
          JsonNode arguments = params != null && params.has("arguments") ? params.get("arguments") : null;

          McpQuery1cResponse mcpResponse = mcpTools.handle(toolName, arguments, requestId);
          response.setStatus(HttpServletResponse.SC_OK);
          response.getWriter().write(MAPPER.writeValueAsString(mcpResponse));

        } catch (Exception e) {
          McpQuery1cResponse errorResp = new McpQuery1cResponse();
          errorResp.setJsonrpc("2.0");
          errorResp.setId("null");
          McpQuery1cResponse.McpError error = new McpQuery1cResponse.McpError();
          error.setCode(-32603);
          error.setMessage("Internal error: " + e.getMessage());
          errorResp.setError(error);
          response.setStatus(HttpServletResponse.SC_OK);
          response.getWriter().write(MAPPER.writeValueAsString(errorResp));
        }
      }
    });
  }

  public void start() throws Exception {
    server.start();
    System.out.println("MCP_QUERY_1C Server started on port " + getPort());
    server.join();
  }

  public void stop() throws Exception {
    server.stop();
  }

  public int getPort() {
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private McpQuery1cResponse buildToolsListResponse(String requestId) {
    McpQuery1cResponse response = new McpQuery1cResponse();
    response.setJsonrpc("2.0");
    response.setId(requestId);
    McpQuery1cResponse.McpResult result = new McpQuery1cResponse.McpResult();

    java.util.List<McpToolDefinition> tools = java.util.List.of(
      tool("add_parameter", "Add parameter from 1C MCP server",
        "name", "string", true,
        "forceRefresh", "boolean", false),
      tool("add_custom_query", "Add custom SQL query",
        "name", "string", true,
        "sqlText", "string", true),
      tool("list_parameters", "List cached parameters",
        "filter", "string", false),
      tool("analyze_parameter", "Re-analyze parameter (refresh from 1C if source=1c)",
        "name", "string", true),
      tool("get_parameter_info", "Get parameter metadata",
        "parameterName", "string", true),
      tool("get_sdbl_model", "Get SDBL model by parameter",
        "parameterName", "string", true),
      tool("get_line_pars_model", "Get LINE_PARS model by parameter",
        "parameterName", "string", true),
      tool("get_full_pars_model", "Get FULL_PARS model by parameter",
        "parameterName", "string", true),
      tool("get_hierarchy", "Get hierarchy by parameter",
        "parameterName", "string", true),
      tool("get_field_lineage", "Get field lineage by parameter",
        "parameterName", "string", true,
        "alias", "string", true,
        "nodeId", "integer", false,
        "nodeName", "string", false),
      tool("get_full_field_lineage", "Get full field lineage by parameter",
        "parameterName", "string", true,
        "aliases", "array", true,
        "nodeId", "integer", false,
        "nodeName", "string", false),
      tool("get_restored_query", "Get restored SQL query by parameter",
        "parameterName", "string", true,
        "aliases", "array", true,
        "format", "string", false,
        "nodeId", "integer", false,
        "nodeName", "string", false),
      tool("get_node_info", "Get node info by parameter",
        "parameterName", "string", true,
        "nodeId", "integer", false,
        "nodeName", "string", false,
        "modelType", "string", false),
      tool("list_nodes", "List nodes by parameter",
        "parameterName", "string", true,
        "nodeType", "string", false,
        "modelType", "string", false),
      tool("get_verification_report", "Get verification report by parameter",
        "parameterName", "string", true)
    );

    try {
      String json = MAPPER.writeValueAsString(java.util.Map.of("tools", tools));
      result.setContent(java.util.List.of(new McpQuery1cResponse.McpContent("text", json)));
    } catch (Exception e) {
      result.setContent(java.util.List.of(new McpQuery1cResponse.McpContent("text", "{\"tools\":[]}")));
    }

    response.setResult(result);
    return response;
  }

  private McpToolDefinition tool(String name, String description, Object... props) {
    java.util.Map<String, McpToolProperty> schema = new java.util.LinkedHashMap<>();
    for (int i = 0; i < props.length; i += 3) {
      schema.put((String) props[i], new McpToolProperty((String) props[i + 1], (String) props[i], (Boolean) props[i + 2]));
    }
    return new McpToolDefinition(name, description, schema);
  }

  private record McpToolDefinition(String name, String description,
                                    java.util.Map<String, McpToolProperty> inputSchema) {}

  private record McpToolProperty(String type, String description, boolean required) {}
}
