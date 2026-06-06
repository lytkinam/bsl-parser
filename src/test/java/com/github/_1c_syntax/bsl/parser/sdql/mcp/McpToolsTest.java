package com.github._1c_syntax.bsl.parser.sdql.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private McpTools mcpTools;

  @BeforeEach
  void setUp() {
    InMemoryModelRepository repository = new InMemoryModelRepository();
    SdqlPipeline pipeline = new SdqlPipelineImpl(repository);
    NodeResolver nodeResolver = new NodeResolverImpl();
    QueryRestorer queryRestorer = new QueryRestorerImpl();
    mcpTools = new McpTools(pipeline, repository, nodeResolver, queryRestorer);
  }

  @Test
  void testAnalyzeSqlPackage() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode args = MAPPER.readTree("{\"sqlText\":\"" + sql + "\"}");

    McpResponse response = mcpTools.handle("analyze_sql_package", args, "1");

    assertThat(response.getError()).isNull();
    assertThat(response.getResult()).isNotNull();
    assertThat(response.getResult().getContent()).isNotEmpty();

    String text = response.getResult().getContent().get(0).getText();
    assertThat(text).contains("baseName");
    assertThat(text).contains("nodesCount");
  }

  @Test
  void testMissingInput() throws Exception {
    JsonNode args = MAPPER.readTree("{}");

    McpResponse response = mcpTools.handle("get_sdbl_model", args, "1");

    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().getMessage()).contains("MISSING_INPUT");
  }

  @Test
  void testUnknownTool() throws Exception {
    JsonNode args = MAPPER.readTree("{}");

    McpResponse response = mcpTools.handle("unknown_tool", args, "1");

    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().getCode()).isEqualTo(-32601);
  }

  @Test
  void testGetSdblModelOnline() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode args = MAPPER.readTree("{\"sqlText\":\"" + sql + "\"}");

    McpResponse response = mcpTools.handle("get_sdbl_model", args, "1");

    assertThat(response.getError()).isNull();
    assertThat(response.getResult()).isNotNull();
  }

  @Test
  void testListNodes() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode args = MAPPER.readTree("{\"sqlText\":\"" + sql + "\"}");

    McpResponse response = mcpTools.handle("list_nodes", args, "1");

    assertThat(response.getError()).isNull();
    String text = response.getResult().getContent().get(0).getText();
    assertThat(text).contains("nodes");
  }
}
