package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpQuery1cToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir
  Path tempDir;

  private McpQuery1cTools mcpQuery1cTools;
  private QueryParameterStore store;

  @BeforeEach
  void setUp() {
    InMemoryModelRepository repository = new InMemoryModelRepository();
    SdqlPipeline pipeline = new SdqlPipelineImpl(repository);
    NodeResolver nodeResolver = new NodeResolverImpl();
    QueryRestorer queryRestorer = new QueryRestorerImpl();
    store = new QueryParameterStore(tempDir);
    // Use a no-op client that returns null for all fetch attempts
    Query1cClient client = new Query1cClient("http://localhost:99999/noop") {
      @Override
      public String fetchQuery(String parameterName) {
        return null;
      }
    };
    mcpQuery1cTools = new McpQuery1cTools(pipeline, store, client, nodeResolver, queryRestorer);
  }

  @Test
  void testAddCustomQuery() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode args = MAPPER.readTree("{\"name\":\"test_query\",\"sqlText\":\"" + sql + "\"}");

    McpQuery1cResponse response = mcpQuery1cTools.handle("add_custom_query", args, "1");

    assertThat(response.getError()).isNull();
    assertThat(response.getResult()).isNotNull();
    assertThat(response.getResult().getContent()).isNotEmpty();

    String text = response.getResult().getContent().get(0).getText();
    assertThat(text).contains("parameterName");
    assertThat(text).contains("test_query");
    assertThat(text).contains("nodesCount");
    assertThat(text).contains("status");
    assertThat(text).contains("created");
  }

  @Test
  void testAddCustomQueryMissingName() throws Exception {
    JsonNode args = MAPPER.readTree("{\"sqlText\":\"ВЫБРАТЬ 1\"}");

    McpQuery1cResponse response = mcpQuery1cTools.handle("add_custom_query", args, "1");

    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().getMessage()).contains("MISSING_INPUT");
  }

  @Test
  void testAddCustomQueryMissingSqlText() throws Exception {
    JsonNode args = MAPPER.readTree("{\"name\":\"test_query\"}");

    McpQuery1cResponse response = mcpQuery1cTools.handle("add_custom_query", args, "1");

    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().getMessage()).contains("MISSING_INPUT");
  }

  @Test
  void testListParameters() throws Exception {
    // Pre-populate store with a custom query
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode addArgs = MAPPER.readTree("{\"name\":\"list_test\",\"sqlText\":\"" + sql + "\"}");
    mcpQuery1cTools.handle("add_custom_query", addArgs, "1");

    JsonNode args = MAPPER.readTree("{}");
    McpQuery1cResponse response = mcpQuery1cTools.handle("list_parameters", args, "2");

    assertThat(response.getError()).isNull();
    assertThat(response.getResult()).isNotNull();
    String text = response.getResult().getContent().get(0).getText();
    assertThat(text).contains("parameters");
    assertThat(text).contains("list_test");
    assertThat(text).contains("total");
  }

  @Test
  void testListParametersWithFilter() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode addArgs = MAPPER.readTree("{\"name\":\"alpha_query\",\"sqlText\":\"" + sql + "\"}");
    mcpQuery1cTools.handle("add_custom_query", addArgs, "1");

    JsonNode args = MAPPER.readTree("{\"filter\":\"alpha\"}");
    McpQuery1cResponse response = mcpQuery1cTools.handle("list_parameters", args, "2");

    assertThat(response.getError()).isNull();
    String text = response.getResult().getContent().get(0).getText();
    assertThat(text).contains("alpha_query");
  }

  @Test
  void testGetFullFieldLineage() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode addArgs = MAPPER.readTree("{\"name\":\"ffl_test\",\"sqlText\":\"" + sql + "\"}");
    mcpQuery1cTools.handle("add_custom_query", addArgs, "1");

    JsonNode args = MAPPER.readTree("{\"parameterName\":\"ffl_test\",\"nodeName\":\"ВТ_Тест\",\"aliases\":[\"Поле1\"]}");
    McpQuery1cResponse response = mcpQuery1cTools.handle("get_full_field_lineage", args, "2");

    assertThat(response.getError()).isNull();
    assertThat(response.getResult()).isNotNull();
    String text = response.getResult().getContent().get(0).getText();
    assertThat(text).contains("parameterName");
    assertThat(text).contains("ffl_test");
    assertThat(text).contains("aliases");
  }

  @Test
  void testGetFullFieldLineageMissingAliases() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode addArgs = MAPPER.readTree("{\"name\":\"ffl_test2\",\"sqlText\":\"" + sql + "\"}");
    mcpQuery1cTools.handle("add_custom_query", addArgs, "1");

    JsonNode args = MAPPER.readTree("{\"parameterName\":\"ffl_test2\",\"nodeName\":\"ВТ_Тест\"}");
    McpQuery1cResponse response = mcpQuery1cTools.handle("get_full_field_lineage", args, "2");

    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().getMessage()).contains("MISSING_INPUT");
  }

  @Test
  void testGetRestoredQuery() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode addArgs = MAPPER.readTree("{\"name\":\"restore_test\",\"sqlText\":\"" + sql + "\"}");
    mcpQuery1cTools.handle("add_custom_query", addArgs, "1");

    JsonNode args = MAPPER.readTree("{\"parameterName\":\"restore_test\",\"nodeName\":\"ВТ_Тест\",\"aliases\":[\"Поле1\"],\"format\":\"sql\"}");
    McpQuery1cResponse response = mcpQuery1cTools.handle("get_restored_query", args, "2");

    assertThat(response.getError()).isNull();
    assertThat(response.getResult()).isNotNull();
    String text = response.getResult().getContent().get(0).getText();
    assertThat(text).contains("parameterName");
    assertThat(text).contains("restore_test");
    assertThat(text).contains("sql");
  }

  @Test
  void testGetRestoredQueryMissingAliases() throws Exception {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";
    JsonNode addArgs = MAPPER.readTree("{\"name\":\"restore_test2\",\"sqlText\":\"" + sql + "\"}");
    mcpQuery1cTools.handle("add_custom_query", addArgs, "1");

    JsonNode args = MAPPER.readTree("{\"parameterName\":\"restore_test2\",\"nodeName\":\"ВТ_Тест\"}");
    McpQuery1cResponse response = mcpQuery1cTools.handle("get_restored_query", args, "2");

    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().getMessage()).contains("MISSING_INPUT");
  }

  @Test
  void testUnknownTool() throws Exception {
    JsonNode args = MAPPER.readTree("{}");

    McpQuery1cResponse response = mcpQuery1cTools.handle("unknown_tool", args, "1");

    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().getCode()).isEqualTo(-32601);
  }

  @Test
  void testAddParameterNotFoundIn1C() throws Exception {
    JsonNode args = MAPPER.readTree("{\"name\":\"nonexistent_param\"}");

    McpQuery1cResponse response = mcpQuery1cTools.handle("add_parameter", args, "1");

    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().getMessage()).contains("NOT_FOUND");
  }
}
