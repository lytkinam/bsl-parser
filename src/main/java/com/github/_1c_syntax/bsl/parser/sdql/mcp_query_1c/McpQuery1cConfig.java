package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration for MCP_QUERY_1C service.
 * Loads settings from properties file or uses defaults.
 */
public class McpQuery1cConfig {

  private static final int DEFAULT_PORT = 8081;
  private static final String DEFAULT_EXTERNAL_MCP_URL = "http://192.168.117.247/npf_ops_users_test_popov/hs/mcp";
  private static final String DEFAULT_STORAGE_DIR = "./mcp_query_1c_storage";

  private final int port;
  private final String externalMcpUrl;
  private final Path storageDir;

  public McpQuery1cConfig(int port, String externalMcpUrl, Path storageDir) {
    this.port = port;
    this.externalMcpUrl = externalMcpUrl;
    this.storageDir = storageDir;
  }

  public static McpQuery1cConfig fromArgs(String[] args) {
    String configFile = "mcp_query_1c.properties";
    int port = DEFAULT_PORT;
    String externalMcpUrl = DEFAULT_EXTERNAL_MCP_URL;
    Path storageDir = Paths.get(DEFAULT_STORAGE_DIR);

    for (String arg : args) {
      if (arg.startsWith("--config=")) {
        configFile = arg.substring("--config=".length());
      } else if (arg.startsWith("--port=")) {
        port = Integer.parseInt(arg.substring("--port=".length()));
      } else if (arg.startsWith("--external-mcp-url=")) {
        externalMcpUrl = arg.substring("--external-mcp-url=".length());
      } else if (arg.startsWith("--storage-dir=")) {
        storageDir = Paths.get(arg.substring("--storage-dir=".length()));
      }
    }

    Path configPath = Paths.get(configFile);
    if (Files.exists(configPath)) {
      Properties props = new Properties();
      try (InputStream is = Files.newInputStream(configPath)) {
        props.load(is);
      } catch (IOException e) {
        System.err.println("Warning: failed to load config file " + configFile + ": " + e.getMessage());
      }
      if (props.containsKey("server.port")) {
        port = Integer.parseInt(props.getProperty("server.port").trim());
      }
      if (props.containsKey("external.mcp.url")) {
        externalMcpUrl = props.getProperty("external.mcp.url").trim();
      }
      if (props.containsKey("storage.dir")) {
        storageDir = Paths.get(props.getProperty("storage.dir").trim());
      }
    }

    return new McpQuery1cConfig(port, externalMcpUrl, storageDir);
  }

  public int getPort() {
    return port;
  }

  public String getExternalMcpUrl() {
    return externalMcpUrl;
  }

  public Path getStorageDir() {
    return storageDir;
  }
}
