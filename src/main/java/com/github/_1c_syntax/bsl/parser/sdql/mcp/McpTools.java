package com.github._1c_syntax.bsl.parser.sdql.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.api.*;
import com.github._1c_syntax.bsl.parser.sdql.api.FflBuilder;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullFieldLineageBuilder;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.FieldLineageNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.HierarchyNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsFieldExtractor;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import com.github._1c_syntax.bsl.parser.sdql.query_extraction.VerificationReport;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.QueryNodeBuilder;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.RestoredQueryNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class McpTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final SdqlPipeline pipeline;
  private final ModelRepository repository;
  private final NodeResolver nodeResolver;
  private final QueryRestorer queryRestorer;

  public McpTools(SdqlPipeline pipeline, ModelRepository repository,
                  NodeResolver nodeResolver, QueryRestorer queryRestorer) {
    this.pipeline = pipeline;
    this.repository = repository;
    this.nodeResolver = nodeResolver;
    this.queryRestorer = queryRestorer;
  }

  public McpResponse handle(String toolName, JsonNode args, String requestId) {
    try {
      return switch (toolName) {
        case "analyze_sql_package" -> handleAnalyze(args, requestId);
        case "get_sdbl_model" -> handleGetSdblModel(args, requestId);
        case "get_line_pars_model" -> handleGetLineParsModel(args, requestId);
        case "get_full_pars_model" -> handleGetFullParsModel(args, requestId);
        case "get_hierarchy" -> handleGetHierarchy(args, requestId);
        case "get_field_lineage" -> handleGetFieldLineage(args, requestId);
        case "get_full_field_lineage" -> handleGetFullFieldLineage(args, requestId);
        case "get_restored_query" -> handleGetRestoredQuery(args, requestId);
        case "get_node_info" -> handleGetNodeInfo(args, requestId);
        case "list_nodes" -> handleListNodes(args, requestId);
        case "get_verification_report" -> handleGetVerificationReport(args, requestId);
        default -> errorResponse(requestId, -32601, "UNKNOWN_TOOL", "Unknown tool: " + toolName);
      };
    } catch (SdqlApiException e) {
      return errorResponse(requestId, -32602, e.getCode(), e.getMessage());
    } catch (Exception e) {
      return errorResponse(requestId, -32603, "INTERNAL_ERROR", e.getMessage());
    }
  }

  /**
   * Convert McpResponse to a Map for direct JSON serialization (Kimi CLI compatible).
   * Unwraps result.content[0].text back to the actual result object.
   */
  public static Map<String, Object> toJsonRpcMap(McpResponse mcpResponse) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("jsonrpc", mcpResponse.getJsonrpc());
    map.put("id", mcpResponse.getId());
    if (mcpResponse.getError() != null) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("code", mcpResponse.getError().getCode());
      err.put("message", mcpResponse.getError().getMessage());
      if (mcpResponse.getError().getData() != null) {
        err.put("data", mcpResponse.getError().getData());
      }
      map.put("error", err);
    } else if (mcpResponse.getResult() != null) {
      List<McpResponse.McpContent> content = mcpResponse.getResult().getContent();
      if (content != null && !content.isEmpty()) {
        String text = content.get(0).getText();
        try {
          map.put("result", MAPPER.readValue(text, Object.class));
        } catch (Exception e) {
          map.put("result", text);
        }
      } else {
        map.put("result", null);
      }
    }
    return map;
  }

  // --- analyze_sql_package ---

  private McpResponse handleAnalyze(JsonNode args, String requestId) {
    String sqlText = getString(args, "sqlText");
    boolean detailed = getBoolean(args, "detailed", false);

    if (sqlText == null) {
      throw new SdqlApiException("MISSING_INPUT", "sqlText is required for analyze_sql_package");
    }

    Path tempDir;
    try {
      tempDir = Files.createTempDirectory("sdql_mcp_");
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to create temp directory", e);
    }

    PipelineResult result = pipeline.analyze(sqlText, tempDir, null, detailed);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("baseName", result.getBaseName());
    response.put("nodesCount", result.getNodesCount());
    response.put("targetNodes", result.getTargetNodes());

    Map<String, String> models = new LinkedHashMap<>();
    models.put("sdblPars", "sdbl_parse_model_" + result.getBaseName() + ".json");
    models.put("linePars", "LINE_PARS/LINE_PARS_model_" + result.getBaseName() + ".json");
    models.put("fullPars", "FULL_PARS/FULL_PARS_model_" + result.getBaseName() + ".json");
    response.put("models", models);

    Map<String, String> artifacts = new LinkedHashMap<>();
    artifacts.put("hierarchy", "LINE_PARS/LINE_PARS_hierarchy_" + result.getBaseName() + ".json");
    artifacts.put("fieldLineage", "field_lineage/" + result.getBaseName() + "/");
    artifacts.put("fullFieldLineage", "full_field_lineage/" + result.getBaseName() + "/");
    artifacts.put("restoredQueries", "RESTORED_QUERIES/" + result.getBaseName() + "/");
    artifacts.put("extractedQueries", "EXTRACTED_QUERIES/" + result.getBaseName() + "/");
    artifacts.put("verification", "VERIFICATION/" + result.getBaseName() + "/verification_report.json");
    response.put("artifacts", artifacts);

    return successResponse(requestId, response);
  }

  // --- get_sdbl_model ---

  private McpResponse handleGetSdblModel(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    QueryModel model = repository.loadSdblModel(ctx.getBaseName());
    return successResponse(requestId, model);
  }

  // --- get_line_pars_model ---

  private McpResponse handleGetLineParsModel(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    LineParsModel model = repository.loadLineParsModel(ctx.getBaseName());
    return successResponse(requestId, model);
  }

  // --- get_full_pars_model ---

  private McpResponse handleGetFullParsModel(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    FullParsModel model = repository.loadFullParsModel(ctx.getBaseName());
    return successResponse(requestId, model);
  }

  // --- get_hierarchy ---

  private McpResponse handleGetHierarchy(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    List<HierarchyNode> hierarchy = repository.loadHierarchy(ctx.getBaseName());
    return successResponse(requestId, hierarchy);
  }

  // --- get_field_lineage ---

  private McpResponse handleGetFieldLineage(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    FullParsModel model = repository.loadFullParsModel(ctx.getBaseName());
    FullParsNode node = resolveNode(model, args);
    String alias = getString(args, "alias");
    if (alias == null) {
      throw new SdqlApiException("MISSING_INPUT", "alias is required");
    }

    FieldLineageNode lineage = repository.loadFieldLineage(ctx.getBaseName(), node.getId(), alias);
    if (lineage == null) {
      // Build on the fly
      lineage = buildFieldLineage(ctx.getBaseName(), node.getId(), alias);
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("baseName", ctx.getBaseName());
    response.put("nodeId", node.getId());
    response.put("nodeName", node.getName());
    response.put("alias", alias);
    response.put("lineage", lineage);
    return successResponse(requestId, response);
  }

  // --- get_full_field_lineage ---

  private McpResponse handleGetFullFieldLineage(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    FullParsModel model = repository.loadFullParsModel(ctx.getBaseName());
    FullParsNode node = resolveNode(model, args);
    List<String> aliases = getStringList(args, "aliases");
    if (aliases == null || aliases.isEmpty()) {
      throw new SdqlApiException("MISSING_INPUT", "aliases is required");
    }

    FflBuilder builder = new FflBuilder();
    List<FullParsNode> fflNodes = builder.build(model, node.getId(), aliases);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("baseName", ctx.getBaseName());
    response.put("nodeId", node.getId());
    response.put("nodeName", node.getName());
    response.put("aliases", aliases);
    response.put("fflNodes", fflNodes);
    return successResponse(requestId, response);
  }

  // --- get_restored_query ---

  private McpResponse handleGetRestoredQuery(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    FullParsModel model = repository.loadFullParsModel(ctx.getBaseName());
    FullParsNode node = resolveNode(model, args);
    List<String> aliases = getStringList(args, "aliases");
    if (aliases == null || aliases.isEmpty()) {
      throw new SdqlApiException("MISSING_INPUT", "aliases is required");
    }
    String format = getString(args, "format", "sql");

    FflBuilder builder = new FflBuilder();
    List<FullParsNode> fflNodes = builder.build(model, node.getId(), aliases);

    Map<Integer, FullParsNode> fullParsById = model.getNodes().stream()
      .collect(Collectors.toMap(FullParsNode::getId, n -> n));

    if ("json".equals(format)) {
      Map<Integer, FullParsNode> fflById = fflNodes.stream()
        .collect(Collectors.toMap(FullParsNode::getId, n -> n));
      QueryNodeBuilder nodeBuilder = new QueryNodeBuilder(fflById, fullParsById);
      if (fflNodes.isEmpty()) {
        throw new SdqlApiException("PIPELINE_ERROR", "No FFL nodes built");
      }
      RestoredQueryNode restored = nodeBuilder.build(fflNodes.get(0));
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("restoredQueryNode", restored);
      return successResponse(requestId, response);
    } else {
      String sql = queryRestorer.restore(fflNodes, fullParsById);
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("baseName", ctx.getBaseName());
      response.put("nodeId", node.getId());
      response.put("nodeName", node.getName());
      response.put("aliases", aliases);
      response.put("sql", sql);
      return successResponse(requestId, response);
    }
  }

  // --- get_node_info ---

  private McpResponse handleGetNodeInfo(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    String modelType = getString(args, "modelType", "full_pars");
    FullParsNode node;

    switch (modelType) {
      case "sdbl" -> {
        QueryModel model = repository.loadSdblModel(ctx.getBaseName());
        node = resolveNodeFromQueryModel(model, args);
      }
      case "line_pars" -> {
        LineParsModel model = repository.loadLineParsModel(ctx.getBaseName());
        node = resolveNodeFromLineParsModel(model, args);
      }
      default -> {
        FullParsModel model = repository.loadFullParsModel(ctx.getBaseName());
        node = resolveNode(model, args);
      }
    }

    return successResponse(requestId, node);
  }

  // --- list_nodes ---

  private McpResponse handleListNodes(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    String modelType = getString(args, "modelType", "full_pars");
    String nodeTypeFilter = getString(args, "nodeType");

    List<Map<String, Object>> nodes = new ArrayList<>();

    switch (modelType) {
      case "sdbl" -> {
        QueryModel model = repository.loadSdblModel(ctx.getBaseName());
        for (var n : model.getNodes()) {
          if (nodeTypeFilter == null || nodeTypeFilter.equals(n.getType())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("name", n.getName());
            m.put("type", n.getType());
            m.put("sdblId", n.getId());
            nodes.add(m);
          }
        }
      }
      case "line_pars" -> {
        LineParsModel model = repository.loadLineParsModel(ctx.getBaseName());
        for (var n : model.getNodes()) {
          if (nodeTypeFilter == null || nodeTypeFilter.equals(n.getType())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("name", n.getName());
            m.put("type", n.getType());
            m.put("sdblId", n.getSdblId());
            nodes.add(m);
          }
        }
      }
      default -> {
        FullParsModel model = repository.loadFullParsModel(ctx.getBaseName());
        for (var n : model.getNodes()) {
          if (nodeTypeFilter == null || nodeTypeFilter.equals(n.getType())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("name", n.getName());
            m.put("type", n.getType());
            m.put("sdblId", n.getSdblId());
            nodes.add(m);
          }
        }
      }
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("nodes", nodes);
    return successResponse(requestId, response);
  }

  // --- get_verification_report ---

  private McpResponse handleGetVerificationReport(JsonNode args, String requestId) {
    PipelineContext ctx = resolveContext(args);
    VerificationReport report;
    try {
      report = repository.loadVerificationReport(ctx.getBaseName());
    } catch (SdqlApiException e) {
      // If report doesn't exist, return empty report
      report = new VerificationReport();
      report.setBaseName(ctx.getBaseName());
      report.setTotalNodes(0);
      report.setMatched(0);
      report.setMismatched(0);
    }
    return successResponse(requestId, report);
  }

  // --- Helpers ---

  private PipelineContext resolveContext(JsonNode args) {
    String sqlText = getString(args, "sqlText");
    String baseName = getString(args, "baseName");

    if (sqlText != null) {
      String generatedBaseName = baseName != null ? baseName : generateBaseName();
      Path tempDir;
      try {
        tempDir = Files.createTempDirectory("sdql_mcp_");
      } catch (IOException e) {
        throw new SdqlApiException("PIPELINE_ERROR", "Failed to create temp directory", e);
      }
      PipelineResult result = pipeline.analyze(sqlText, tempDir, generatedBaseName, false);
      // Persist artifacts from tempDir to repository so Offline mode can find them
      if (repository instanceof com.github._1c_syntax.bsl.parser.sdql.api.FileSystemModelRepository fsRepo) {
        ArtifactStore store = new ArtifactStore(fsRepo.getBaseDir());
        store.persist(tempDir, result.getBaseName());
      }
      return new PipelineContext(result.getBaseName(), tempDir, true);
    } else if (baseName != null) {
      if (!repository.exists(baseName)) {
        throw new SdqlApiException("ARTIFACT_NOT_FOUND", "Artifacts not found: " + baseName);
      }
      return new PipelineContext(baseName, null, false);
    } else {
      throw new SdqlApiException("MISSING_INPUT", "Either sqlText or baseName must be provided");
    }
  }

  private FullParsNode resolveNode(FullParsModel model, JsonNode args) {
    Integer nodeId = getInt(args, "nodeId");
    String nodeName = getString(args, "nodeName");
    return nodeResolver.resolve(model, nodeId, nodeName);
  }

  private FullParsNode resolveNodeFromQueryModel(QueryModel model, JsonNode args) {
    Integer nodeId = getInt(args, "nodeId");
    String nodeName = getString(args, "nodeName");
    if (nodeId != null) {
      for (var n : model.getNodes()) {
        if (nodeId.equals(n.getId())) {
          FullParsNode fp = new FullParsNode();
          fp.setId(n.getId());
          fp.setName(n.getName());
          fp.setType(n.getType());
          return fp;
        }
      }
      throw new NodeNotFoundException("Node not found: id=" + nodeId);
    }
    if (nodeName != null) {
      for (var n : model.getNodes()) {
        if (nodeName.equals(n.getName())) {
          FullParsNode fp = new FullParsNode();
          fp.setId(n.getId());
          fp.setName(n.getName());
          fp.setType(n.getType());
          return fp;
        }
      }
      throw new NodeNotFoundException("Node not found: name=" + nodeName);
    }
    throw new SdqlApiException("MISSING_NODE", "Either nodeId or nodeName must be provided");
  }

  private FullParsNode resolveNodeFromLineParsModel(LineParsModel model, JsonNode args) {
    Integer nodeId = getInt(args, "nodeId");
    String nodeName = getString(args, "nodeName");
    if (nodeId != null) {
      for (var n : model.getNodes()) {
        if (nodeId.equals(n.getId())) {
          FullParsNode fp = new FullParsNode();
          fp.setId(n.getId());
          fp.setName(n.getName());
          fp.setType(n.getType());
          fp.setSdblId(n.getSdblId());
          return fp;
        }
      }
      throw new NodeNotFoundException("Node not found: id=" + nodeId);
    }
    if (nodeName != null) {
      for (var n : model.getNodes()) {
        if (nodeName.equals(n.getName())) {
          FullParsNode fp = new FullParsNode();
          fp.setId(n.getId());
          fp.setName(n.getName());
          fp.setType(n.getType());
          fp.setSdblId(n.getSdblId());
          return fp;
        }
      }
      throw new NodeNotFoundException("Node not found: name=" + nodeName);
    }
    throw new SdqlApiException("MISSING_NODE", "Either nodeId or nodeName must be provided");
  }

  private FieldLineageNode buildFieldLineage(String baseName, int nodeId, String alias) {
    // Build field lineage on-the-fly using LineParsFieldExtractor
    try {
      LineParsModel lineParsModel = repository.loadLineParsModel(baseName);
      List<HierarchyNode> hierarchy = repository.loadHierarchy(baseName);
      if (lineParsModel == null || hierarchy == null || hierarchy.isEmpty()) {
        FieldLineageNode node = new FieldLineageNode();
        node.setId(nodeId);
        node.setAlias(alias);
        return node;
      }
      LineParsFieldExtractor extractor = new LineParsFieldExtractor(lineParsModel, hierarchy);
      return extractor.extract(nodeId, alias);
    } catch (Exception e) {
      FieldLineageNode node = new FieldLineageNode();
      node.setId(nodeId);
      node.setAlias(alias);
      return node;
    }
  }



  private String getString(JsonNode node, String field) {
    if (node == null || !node.has(field)) return null;
    JsonNode value = node.get(field);
    return value.isNull() ? null : value.asText();
  }

  private String getString(JsonNode node, String field, String defaultValue) {
    String value = getString(node, field);
    return value != null ? value : defaultValue;
  }

  private Integer getInt(JsonNode node, String field) {
    if (node == null || !node.has(field)) return null;
    JsonNode value = node.get(field);
    return value.isNull() ? null : value.asInt();
  }

  private boolean getBoolean(JsonNode node, String field, boolean defaultValue) {
    if (node == null || !node.has(field)) return defaultValue;
    JsonNode value = node.get(field);
    return value.isNull() ? defaultValue : value.asBoolean();
  }

  @SuppressWarnings("unchecked")
  private List<String> getStringList(JsonNode node, String field) {
    if (node == null || !node.has(field)) return null;
    JsonNode value = node.get(field);
    if (value.isNull() || !value.isArray()) return null;
    List<String> result = new ArrayList<>();
    for (JsonNode item : value) {
      result.add(item.asText());
    }
    return result;
  }

  private String generateBaseName() {
    return "tmp_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private McpResponse successResponse(String requestId, Object data) {
    McpResponse response = new McpResponse();
    response.setJsonrpc("2.0");
    response.setId(requestId);
    try {
      String json = MAPPER.writeValueAsString(data);
      response.setResult(McpResponse.McpResult.text(json));
    } catch (Exception e) {
      response.setResult(McpResponse.McpResult.text("{}"));
    }
    return response;
  }

  private McpResponse errorResponse(String requestId, int code, String errorCode, String message) {
    McpResponse response = new McpResponse();
    response.setJsonrpc("2.0");
    response.setId(requestId);
    McpResponse.McpError error = new McpResponse.McpError();
    error.setCode(code);
    error.setMessage(errorCode + ": " + message);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("details", message);
    error.setData(data);
    response.setError(error);
    return response;
  }
}
