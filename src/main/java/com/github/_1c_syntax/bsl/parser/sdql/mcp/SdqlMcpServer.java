package com.github._1c_syntax.bsl.parser.sdql.mcp;

import com.github._1c_syntax.bsl.parser.sdql.api.*;

import java.nio.file.Path;

public class SdqlMcpServer {

  public static void main(String[] args) throws Exception {
    int port = 8080;
    Path artifactsDir = Path.of("./artifacts");
    Path tempDir = Path.of("./tmp");

    for (String arg : args) {
      if (arg.startsWith("--port=")) {
        port = Integer.parseInt(arg.substring("--port=".length()));
      } else if (arg.startsWith("--artifacts-dir=")) {
        artifactsDir = Path.of(arg.substring("--artifacts-dir=".length()));
      } else if (arg.startsWith("--temp-dir=")) {
        tempDir = Path.of(arg.substring("--temp-dir=".length()));
      }
    }

    System.out.println("Starting SDQL MCP Server...");
    System.out.println("Port: " + port);
    System.out.println("Artifacts dir: " + artifactsDir.toAbsolutePath());
    System.out.println("Temp dir: " + tempDir.toAbsolutePath());

    ModelRepository repository = new FileSystemModelRepository(artifactsDir);
    SdqlPipeline pipeline = new SdqlPipelineImpl(repository);
    NodeResolver nodeResolver = new NodeResolverImpl();
    QueryRestorer queryRestorer = new QueryRestorerImpl();

    McpTools mcpTools = new McpTools(pipeline, repository, nodeResolver, queryRestorer);
    McpHttpServer server = new McpHttpServer(port, mcpTools);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        System.out.println("Shutting down MCP Server...");
        server.stop();
      } catch (Exception e) {
        System.err.println("Error stopping server: " + e.getMessage());
      }
    }));

    server.start();
  }
}
