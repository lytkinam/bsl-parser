package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import com.github._1c_syntax.bsl.parser.sdql.api.*;

import java.nio.file.Path;

/**
 * Entry point for MCP_QUERY_1C service.
 * <p>
 * Usage:
 * <pre>
 *   java ... McpQuery1cServer [--port=8081] [--external-mcp-url=...] [--storage-dir=...] [--config=file.properties]
 * </pre>
 */
public class McpQuery1cServer {

  public static void main(String[] args) throws Exception {
    McpQuery1cConfig config = McpQuery1cConfig.fromArgs(args);

    System.out.println("Starting MCP_QUERY_1C Server...");
    System.out.println("Port: " + config.getPort());
    System.out.println("External MCP URL: " + config.getExternalMcpUrl());
    System.out.println("Storage dir: " + config.getStorageDir().toAbsolutePath());

    // Storage for cached parameters
    QueryParameterStore store = new QueryParameterStore(config.getStorageDir());

    // Client for 1C MCP server
    Query1cClient client = new Query1cClient(config.getExternalMcpUrl());

    // Test connectivity
    if (client.isAvailable()) {
      System.out.println("1C MCP server is available.");
    } else {
      System.out.println("WARNING: 1C MCP server is NOT available. add_parameter will fail, but add_custom_query works.");
    }

    // SDQL pipeline and helpers
    Path artifactsDir = config.getStorageDir().resolve("_temp_artifacts");
    ModelRepository repository = new FileSystemModelRepository(artifactsDir);
    SdqlPipeline pipeline = new SdqlPipelineImpl(repository);
    NodeResolver nodeResolver = new NodeResolverImpl();
    QueryRestorer queryRestorer = new QueryRestorerImpl();

    McpQuery1cTools mcpTools = new McpQuery1cTools(pipeline, store, client, nodeResolver, queryRestorer);
    McpQuery1cHttpServer server = new McpQuery1cHttpServer(config.getPort(), mcpTools);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        System.out.println("Shutting down MCP_QUERY_1C Server...");
        server.stop();
      } catch (Exception e) {
        System.err.println("Error stopping server: " + e.getMessage());
      }
    }));

    server.start();
  }
}
