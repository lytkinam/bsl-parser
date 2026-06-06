package com.github._1c_syntax.bsl.parser.sdql.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.IOException;

public class McpHttpServer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Server server;
  private final McpTools mcpTools;

  public McpHttpServer(int port, McpTools mcpTools) {
    this.mcpTools = mcpTools;

    QueuedThreadPool threadPool = new QueuedThreadPool();
    threadPool.setName("sdql-mcp");
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

          if ("initialize".equals(method)) {
            // Initialize must return result directly (not wrapped in content/text)
            java.util.Map<String, Object> initResult = new java.util.LinkedHashMap<>();
            initResult.put("protocolVersion", "2025-03-26");
            java.util.Map<String, Object> caps = new java.util.LinkedHashMap<>();
            java.util.Map<String, Object> toolsCap = new java.util.LinkedHashMap<>();
            toolsCap.put("listChanged", false);
            caps.put("tools", toolsCap);
            initResult.put("capabilities", caps);
            initResult.put("serverInfo", java.util.Map.of("name", "sdql-mcp", "version", "1.0"));

            java.util.Map<String, Object> initResp = new java.util.LinkedHashMap<>();
            initResp.put("jsonrpc", "2.0");
            initResp.put("id", requestId);
            initResp.put("result", initResult);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(MAPPER.writeValueAsString(initResp));
            return;
          }

          if ("notifications/initialized".equals(method)) {
            // Notification, no response needed
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{}");
            return;
          }

          if (!"tools/call".equals(method) && !"tools/list".equals(method)) {
            McpResponse errorResp = new McpResponse();
            errorResp.setJsonrpc("2.0");
            errorResp.setId(requestId);
            McpResponse.McpError error = new McpResponse.McpError();
            error.setCode(-32601);
            error.setMessage("Method not found: " + method);
            errorResp.setError(error);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(MAPPER.writeValueAsString(errorResp));
            return;
          }

          if ("tools/list".equals(method)) {
            McpResponse listResp = buildToolsListResponse(requestId);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(MAPPER.writeValueAsString(McpTools.toJsonRpcMap(listResp)));
            return;
          }

          JsonNode params = jsonRequest.has("params") ? jsonRequest.get("params") : null;
          String toolName = params != null && params.has("name") ? params.get("name").asText() : "";
          JsonNode arguments = params != null && params.has("arguments") ? params.get("arguments") : null;

          McpResponse mcpResponse = mcpTools.handle(toolName, arguments, requestId);
          response.setStatus(HttpServletResponse.SC_OK);
          response.getWriter().write(MAPPER.writeValueAsString(McpTools.toJsonRpcMap(mcpResponse)));

        } catch (Exception e) {
          McpResponse errorResp = new McpResponse();
          errorResp.setJsonrpc("2.0");
          errorResp.setId("null");
          McpResponse.McpError error = new McpResponse.McpError();
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
    System.out.println("MCP Server started on port " + getPort());
    server.join();
  }

  public void stop() throws Exception {
    server.stop();
  }

  public int getPort() {
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private McpResponse buildToolsListResponse(String requestId) {
    McpResponse response = new McpResponse();
    response.setJsonrpc("2.0");
    response.setId(requestId);
    McpResponse.McpResult result = new McpResponse.McpResult();

    java.util.List<McpToolDefinition> tools = java.util.List.of(
      new McpToolDefinition("analyze_sql_package", "Analyze SQL package and return metadata",
        java.util.Map.of("sqlText", new McpToolProperty("string", "SQL text", true))),
      new McpToolDefinition("get_sdbl_model", "Get SDBL model",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false))),
      new McpToolDefinition("get_line_pars_model", "Get LINE_PARS model",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false))),
      new McpToolDefinition("get_full_pars_model", "Get FULL_PARS model",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false))),
      new McpToolDefinition("get_hierarchy", "Get node hierarchy",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false))),
      new McpToolDefinition("get_field_lineage", "Get field lineage",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false),
          "alias", new McpToolProperty("string", "Field alias", true))),
      new McpToolDefinition("get_full_field_lineage", "Get full field lineage",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false),
          "aliases", new McpToolProperty("array", "Field aliases", true))),
      new McpToolDefinition("get_restored_query", "Get restored SQL query",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false),
          "aliases", new McpToolProperty("array", "Field aliases", true))),
      new McpToolDefinition("get_node_info", "Get node info",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false))),
      new McpToolDefinition("list_nodes", "List all nodes",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false))),
      new McpToolDefinition("get_verification_report", "Get verification report",
        java.util.Map.of("baseName", new McpToolProperty("string", "Base name", false)))
    );

    try {
      java.util.Map<String, Object> schema = new java.util.LinkedHashMap<>();
      schema.put("tools", tools);
      String json = MAPPER.writeValueAsString(schema);
      result.setContent(java.util.List.of(new McpResponse.McpContent("text", json)));
    } catch (Exception e) {
      result.setContent(java.util.List.of(new McpResponse.McpContent("text", "{\"tools\":[]}")));
    }

    response.setResult(result);
    return response;
  }

  // Simple tool definition for tools/list
  private record McpToolDefinition(String name, String description,
                                    java.util.Map<String, McpToolProperty> inputSchema) {}

  private record McpToolProperty(String type, String description, boolean required) {}
}
