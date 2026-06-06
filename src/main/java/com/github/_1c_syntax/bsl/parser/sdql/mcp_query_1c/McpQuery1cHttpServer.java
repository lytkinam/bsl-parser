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
import java.io.PrintWriter;
import java.util.*;

/**
 * HTTP server for MCP_QUERY_1C service.
 * Handles JSON-RPC MCP requests on /mcp endpoint with SSE support for Kimi CLI.
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

        if ("/health".equals(target) && "GET".equalsIgnoreCase(request.getMethod())) {
          response.setContentType("application/json; charset=UTF-8");
          response.setStatus(HttpServletResponse.SC_OK);
          response.getWriter().write("{\"status\":\"ok\"}");
          return;
        }

        if (!"/mcp".equals(target) || !"POST".equalsIgnoreCase(request.getMethod())) {
          response.setContentType("application/json; charset=UTF-8");
          response.setStatus(HttpServletResponse.SC_NOT_FOUND);
          response.getWriter().write("{\"error\":\"Not found\"}");
          return;
        }

        // Check if client accepts SSE
        String accept = request.getHeader("Accept");
        boolean acceptsSse = accept != null && accept.contains("text/event-stream");

        if (acceptsSse) {
          handleSse(request, response);
        } else {
          handleJson(request, response);
        }
      }
    });
  }

  private void handleSse(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("text/event-stream; charset=UTF-8");
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("mcp-session-id", UUID.randomUUID().toString().replace("-", ""));
    response.setStatus(HttpServletResponse.SC_OK);

    PrintWriter writer = response.getWriter();
    JsonNode jsonRequest = MAPPER.readTree(request.getInputStream());
    String requestId = jsonRequest.has("id") ? jsonRequest.get("id").asText() : "null";
    String method = jsonRequest.has("method") ? jsonRequest.get("method").asText() : "";

    Map<String, Object> result;
    if ("initialize".equals(method)) {
      result = buildInitializeResult(requestId);
    } else if ("notifications/initialized".equals(method)) {
      writer.flush();
      return;
    } else if ("tools/list".equals(method)) {
      result = buildToolsListMap(requestId);
    } else if ("tools/call".equals(method)) {
      JsonNode params = jsonRequest.has("params") ? jsonRequest.get("params") : null;
      String toolName = params != null && params.has("name") ? params.get("name").asText() : "";
      JsonNode arguments = params != null && params.has("arguments") ? params.get("arguments") : null;
      result = mcpTools.handleToMap(toolName, arguments, requestId);
    } else {
      result = errorMap(requestId, -32601, "Method not found: " + method);
    }

    String json = MAPPER.writeValueAsString(result);
    writer.write("event: message\n");
    writer.write("data: " + json.replace("\n", "") + "\n\n");
    writer.flush();
  }

  private void handleJson(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("application/json; charset=UTF-8");
    response.setStatus(HttpServletResponse.SC_OK);

    JsonNode jsonRequest = MAPPER.readTree(request.getInputStream());
    String requestId = jsonRequest.has("id") ? jsonRequest.get("id").asText() : "null";
    String method = jsonRequest.has("method") ? jsonRequest.get("method").asText() : "";

    Map<String, Object> result;
    if ("initialize".equals(method)) {
      result = buildInitializeResult(requestId);
    } else if ("notifications/initialized".equals(method)) {
      response.getWriter().write("{}");
      return;
    } else if ("tools/list".equals(method)) {
      result = buildToolsListMap(requestId);
    } else if ("tools/call".equals(method)) {
      JsonNode params = jsonRequest.has("params") ? jsonRequest.get("params") : null;
      String toolName = params != null && params.has("name") ? params.get("name").asText() : "";
      JsonNode arguments = params != null && params.has("arguments") ? params.get("arguments") : null;
      result = mcpTools.handleToMap(toolName, arguments, requestId);
    } else {
      result = errorMap(requestId, -32601, "Method not found: " + method);
    }

    response.getWriter().write(MAPPER.writeValueAsString(result));
  }

  private Map<String, Object> buildInitializeResult(String requestId) {
    Map<String, Object> initResult = new LinkedHashMap<>();
    initResult.put("protocolVersion", "2025-03-26");
    Map<String, Object> caps = new LinkedHashMap<>();
    Map<String, Object> toolsCap = new LinkedHashMap<>();
    toolsCap.put("listChanged", false);
    caps.put("tools", toolsCap);
    initResult.put("capabilities", caps);
    initResult.put("serverInfo", Map.of("name", "mcp-query-1c", "version", "1.0"));

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("jsonrpc", "2.0");
    resp.put("id", requestId);
    resp.put("result", initResult);
    return resp;
  }

  private Map<String, Object> errorMap(String requestId, int code, String message) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("code", code);
    err.put("message", message);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("jsonrpc", "2.0");
    resp.put("id", requestId);
    resp.put("error", err);
    return resp;
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

  private Map<String, Object> buildToolsListMap(String requestId) {
    List<Map<String, Object>> tools = List.of(
      tool("add_parameter", "Add parameter from 1C MCP server",
        prop("name", "string", "Parameter name in 1C", true),
        prop("forceRefresh", "boolean", "Force refresh from 1C", false)),
      tool("add_custom_query", "Add custom SQL query",
        prop("name", "string", "Query name", true),
        prop("sqlText", "string", "SQL text", true)),
      tool("list_parameters", "List cached parameters",
        prop("filter", "string", "Filter by name substring", false)),
      tool("analyze_parameter", "Re-analyze parameter (refresh from 1C if source=1c)",
        prop("name", "string", "Parameter name", true)),
      tool("get_parameter_info", "Get parameter metadata",
        prop("parameterName", "string", "Parameter name", true)),
      tool("get_sdbl_model", "Get SDBL model by parameter",
        prop("parameterName", "string", "Parameter name", true)),
      tool("get_line_pars_model", "Get LINE_PARS model by parameter",
        prop("parameterName", "string", "Parameter name", true)),
      tool("get_full_pars_model", "Get FULL_PARS model by parameter",
        prop("parameterName", "string", "Parameter name", true)),
      tool("get_hierarchy", "Get hierarchy by parameter",
        prop("parameterName", "string", "Parameter name", true)),
      tool("get_field_lineage", "Get field lineage by parameter",
        prop("parameterName", "string", "Parameter name", true),
        prop("alias", "string", "Field alias", true),
        prop("nodeId", "integer", "Node ID", false),
        prop("nodeName", "string", "Node name", false)),
      tool("get_full_field_lineage", "Get full field lineage by parameter",
        prop("parameterName", "string", "Parameter name", true),
        prop("aliases", "array", "Field aliases", true),
        prop("nodeId", "integer", "Node ID", false),
        prop("nodeName", "string", "Node name", false)),
      tool("get_restored_query", "Get restored SQL query by parameter",
        prop("parameterName", "string", "Parameter name", true),
        prop("aliases", "array", "Field aliases", true),
        prop("format", "string", "Output format: sql or json", false),
        prop("nodeId", "integer", "Node ID", false),
        prop("nodeName", "string", "Node name", false)),
      tool("get_node_info", "Get node info by parameter",
        prop("parameterName", "string", "Parameter name", true),
        prop("nodeId", "integer", "Node ID", false),
        prop("nodeName", "string", "Node name", false),
        prop("modelType", "string", "Model type: sdbl, line_pars, full_pars", false)),
      tool("list_nodes", "List nodes by parameter",
        prop("parameterName", "string", "Parameter name", true),
        prop("nodeType", "string", "Filter by node type", false),
        prop("modelType", "string", "Model type: sdbl, line_pars, full_pars", false)),
      tool("get_verification_report", "Get verification report by parameter",
        prop("parameterName", "string", "Parameter name", true))
    );

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tools", tools);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("jsonrpc", "2.0");
    resp.put("id", requestId);
    resp.put("result", result);
    return resp;
  }

  private Map<String, Object> tool(String name, String description, Map<String, Object>... props) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (Map<String, Object> p : props) {
      String propName = (String) p.get("name");
      properties.put(propName, Map.of(
        "type", p.get("type"),
        "description", p.get("description")
      ));
      if (Boolean.TRUE.equals(p.get("required"))) {
        required.add(propName);
      }
    }
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }

    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("name", name);
    tool.put("description", description);
    tool.put("inputSchema", schema);
    return tool;
  }

  private Map<String, Object> prop(String name, String type, String description, boolean required) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("name", name);
    p.put("type", type);
    p.put("description", description);
    p.put("required", required);
    return p;
  }
}
