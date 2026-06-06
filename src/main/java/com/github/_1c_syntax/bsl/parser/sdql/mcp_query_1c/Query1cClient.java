package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for calling 1c-mcp-server-popov.
 * Handles pagination via part_query for large queries (>100000 chars).
 */
public class Query1cClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_PARTS = 100; // Safety limit

  private final String baseUrl;
  private final HttpClient httpClient;

  public Query1cClient(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .build();
  }

  /**
   * Fetch a query by parameter name from 1C MCP server.
   * Automatically handles pagination if the query is split into parts.
   *
   * @param parameterName exact parameter name
   * @return full SQL query text, or null if not found
   */
  public String fetchQuery(String parameterName) throws IOException, InterruptedException {
    StringBuilder fullQuery = new StringBuilder();

    for (int part = 0; part < MAX_PARTS; part++) {
      String chunk = fetchQueryPart(parameterName, part);
      if (chunk == null || chunk.isEmpty()) {
        // Empty response means end of query
        break;
      }
      fullQuery.append(chunk);
    }

    String result = fullQuery.toString();
    return result.isEmpty() ? null : result;
  }

  /**
   * Fetch a single part of a query.
   *
   * @param parameterName exact parameter name
   * @param partQuery     part number (0-based)
   * @return query text chunk, or null/empty if no more parts
   */
  private String fetchQueryPart(String parameterName, int partQuery) throws IOException, InterruptedException {
    String requestBody = MAPPER.writeValueAsString(java.util.Map.of(
      "jsonrpc", "2.0",
      "id", 1,
      "method", "tools/call",
      "params", java.util.Map.of(
        "name", "list_query_param",
        "arguments", java.util.Map.of(
          "name", parameterName,
          "part_query", partQuery
        )
      )
    ));

    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(requestBody))
      .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode root = MAPPER.readTree(response.body());

    if (root.has("error")) {
      JsonNode error = root.get("error");
      String message = error.has("message") ? error.get("message").asText() : "Unknown error";
      throw new IOException("MCP error: " + message);
    }

    JsonNode result = root.get("result");
    if (result == null || !result.has("content")) {
      return null;
    }

    JsonNode content = result.get("content");
    if (!content.isArray() || content.isEmpty()) {
      return null;
    }

    JsonNode firstContent = content.get(0);
    if (!firstContent.has("text")) {
      return null;
    }

    String text = firstContent.get("text").asText();

    // The response from list_query_param is a JSON array with one string element
    // Try to parse it as JSON array
    try {
      JsonNode arrayNode = MAPPER.readTree(text);
      if (arrayNode.isArray() && !arrayNode.isEmpty()) {
        return arrayNode.get(0).asText();
      }
    } catch (Exception e) {
      // Not a JSON array, return raw text
      return text;
    }

    return text;
  }

  /**
   * Test connectivity to the 1C MCP server.
   */
  public boolean isAvailable() {
    try {
      String requestBody = MAPPER.writeValueAsString(java.util.Map.of(
        "jsonrpc", "2.0",
        "id", 1,
        "method", "tools/list"
      ));

      HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    } catch (Exception e) {
      return false;
    }
  }
}
