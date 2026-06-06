package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.api.*;
import com.github._1c_syntax.bsl.parser.sdql.api.FflBuilder;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.FieldLineageNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.HierarchyNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import com.github._1c_syntax.bsl.parser.sdql.query_extraction.VerificationReport;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.QueryNodeBuilder;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.RestoredQueryNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool handler for MCP_QUERY_1C service.
 * Wraps BRD06 methods to work with cached 1C query parameters.
 */
public class McpQuery1cTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final SdqlPipeline pipeline;
  private final QueryParameterStore store;
  private final Query1cClient client;
  private final NodeResolver nodeResolver;
  private final QueryRestorer queryRestorer;

  public McpQuery1cTools(SdqlPipeline pipeline, QueryParameterStore store,
                         Query1cClient client, NodeResolver nodeResolver,
                         QueryRestorer queryRestorer) {
    this.pipeline = pipeline;
    this.store = store;
    this.client = client;
    this.nodeResolver = nodeResolver;
    this.queryRestorer = queryRestorer;
  }

  public McpQuery1cResponse handle(String toolName, JsonNode args, String requestId) {
    try {
      return switch (toolName) {
        case "add_parameter" -> handleAddParameter(args, requestId);
        case "add_custom_query" -> handleAddCustomQuery(args, requestId);
        case "list_parameters" -> handleListParameters(args, requestId);
        case "analyze_parameter" -> handleAnalyzeParameter(args, requestId);
        case "get_parameter_info" -> handleGetParameterInfo(args, requestId);
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
   * Handle tool call and return a Map for direct JSON serialization (Kimi CLI compatible).
   * Unwraps result.content[0].text back to the actual result object.
   */
  public Map<String, Object> handleToMap(String toolName, JsonNode args, String requestId) {
    McpQuery1cResponse response = handle(toolName, args, requestId);
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("jsonrpc", response.getJsonrpc());
    map.put("id", response.getId());
    if (response.getError() != null) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("code", response.getError().getCode());
      err.put("message", response.getError().getMessage());
      if (response.getError().getData() != null) {
        err.put("data", response.getError().getData());
      }
      map.put("error", err);
    } else if (response.getResult() != null) {
      List<McpQuery1cResponse.McpContent> content = response.getResult().getContent();
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

  // --- add_parameter ---

  private McpQuery1cResponse handleAddParameter(JsonNode args, String requestId) throws Exception {
    String name = getString(args, "name");
    boolean forceRefresh = getBoolean(args, "forceRefresh", false);

    if (name == null || name.isBlank()) {
      throw new SdqlApiException("MISSING_INPUT", "name is required");
    }

    Optional<QueryParameter> existing = store.findByName(name);
    if (existing.isPresent() && !forceRefresh) {
      QueryParameter p = existing.get();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("parameterName", p.getName());
      response.put("baseName", p.getBaseName());
      response.put("nodesCount", p.getNodesCount());
      response.put("status", "exists");
      response.put("updatedAt", p.getUpdatedAt().toString());
      return successResponse(requestId, response);
    }

    // Fetch from 1C
    String sqlText = client.fetchQuery(name);
    if (sqlText == null) {
      throw new SdqlApiException("NOT_FOUND", "Parameter not found in 1C: " + name);
    }

    // Analyze
    String baseName = store.sanitizeName(name);
    Path artifactsDir = store.getArtifactsDir(name);
    Files.createDirectories(artifactsDir);

    PipelineResult result = pipeline.analyze(sqlText, artifactsDir, baseName, false);

    // Save parameter metadata
    QueryParameter param = new QueryParameter();
    param.setName(name);
    param.setSqlText(sqlText);
    param.setBaseName(baseName);
    param.setUpdatedAt(Instant.now());
    param.setSource(QueryParameter.Source.ONEC);
    param.setNodesCount(result.getNodesCount());
    store.save(param);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("parameterName", name);
    response.put("baseName", baseName);
    response.put("nodesCount", result.getNodesCount());
    response.put("status", existing.isPresent() ? "updated" : "created");
    response.put("updatedAt", param.getUpdatedAt().toString());
    return successResponse(requestId, response);
  }

  // --- add_custom_query ---

  private McpQuery1cResponse handleAddCustomQuery(JsonNode args, String requestId) throws Exception {
    String name = getString(args, "name");
    String sqlText = getString(args, "sqlText");

    if (name == null || name.isBlank()) {
      throw new SdqlApiException("MISSING_INPUT", "name is required");
    }
    if (sqlText == null || sqlText.isBlank()) {
      throw new SdqlApiException("MISSING_INPUT", "sqlText is required");
    }

    String baseName = store.sanitizeName(name);
    Path artifactsDir = store.getArtifactsDir(name);
    Files.createDirectories(artifactsDir);

    PipelineResult result = pipeline.analyze(sqlText, artifactsDir, baseName, false);

    QueryParameter param = new QueryParameter();
    param.setName(name);
    param.setSqlText(sqlText);
    param.setBaseName(baseName);
    param.setUpdatedAt(Instant.now());
    param.setSource(QueryParameter.Source.CUSTOM);
    param.setNodesCount(result.getNodesCount());
    store.save(param);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("parameterName", name);
    response.put("baseName", baseName);
    response.put("nodesCount", result.getNodesCount());
    response.put("status", "created");
    response.put("updatedAt", param.getUpdatedAt().toString());
    return successResponse(requestId, response);
  }

  // --- list_parameters ---

  private McpQuery1cResponse handleListParameters(JsonNode args, String requestId) {
    String filter = getString(args, "filter");
    List<QueryParameter> params = store.listAll(filter);

    List<Map<String, Object>> items = params.stream().map(p -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", p.getName());
      m.put("baseName", p.getBaseName());
      m.put("updatedAt", p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);
      m.put("nodesCount", p.getNodesCount());
      m.put("source", p.getSource() != null ? p.getSource().name().toLowerCase() : null);
      return m;
    }).collect(Collectors.toList());

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("parameters", items);
    response.put("total", items.size());
    return successResponse(requestId, response);
  }

  // --- analyze_parameter ---

  private McpQuery1cResponse handleAnalyzeParameter(JsonNode args, String requestId) throws Exception {
    String name = getString(args, "name");
    if (name == null || name.isBlank()) {
      throw new SdqlApiException("MISSING_INPUT", "name is required");
    }

    Optional<QueryParameter> opt = store.findByName(name);
    if (opt.isEmpty()) {
      throw new SdqlApiException("NOT_FOUND", "Parameter not found: " + name);
    }

    QueryParameter param = opt.get();
    String sqlText;

    if (param.getSource() == QueryParameter.Source.ONEC) {
      // Re-fetch from 1C
      sqlText = client.fetchQuery(name);
      if (sqlText == null) {
        throw new SdqlApiException("NOT_FOUND", "Parameter no longer available in 1C: " + name);
      }
    } else {
      sqlText = param.getSqlText();
    }

    String baseName = store.sanitizeName(name);
    Path artifactsDir = store.getArtifactsDir(name);
    Files.createDirectories(artifactsDir);

    PipelineResult result = pipeline.analyze(sqlText, artifactsDir, baseName, false);

    param.setSqlText(sqlText);
    param.setBaseName(baseName);
    param.setUpdatedAt(Instant.now());
    param.setNodesCount(result.getNodesCount());
    store.save(param);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("parameterName", name);
    response.put("baseName", baseName);
    response.put("nodesCount", result.getNodesCount());
    response.put("status", "reanalyzed");
    response.put("updatedAt", param.getUpdatedAt().toString());
    return successResponse(requestId, response);
  }

  // --- get_parameter_info ---

  private McpQuery1cResponse handleGetParameterInfo(JsonNode args, String requestId) {
    String name = getString(args, "parameterName");
    if (name == null || name.isBlank()) {
      throw new SdqlApiException("MISSING_INPUT", "parameterName is required");
    }

    Optional<QueryParameter> opt = store.findByName(name);
    if (opt.isEmpty()) {
      throw new SdqlApiException("NOT_FOUND", "Parameter not found: " + name);
    }

    QueryParameter p = opt.get();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("name", p.getName());
    response.put("baseName", p.getBaseName());
    response.put("updatedAt", p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);
    response.put("nodesCount", p.getNodesCount());
    response.put("source", p.getSource() != null ? p.getSource().name().toLowerCase() : null);
    response.put("sqlLength", p.getSqlText() != null ? p.getSqlText().length() : 0);
    return successResponse(requestId, response);
  }

  // --- Proxy methods (BRD06 equivalents) ---

  private McpQuery1cResponse handleGetSdblModel(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    ModelRepository repo = createRepoForParameter(parameterName);
    QueryModel model = repo.loadSdblModel(baseName);
    return successResponse(requestId, model);
  }

  private McpQuery1cResponse handleGetLineParsModel(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    ModelRepository repo = createRepoForParameter(parameterName);
    LineParsModel model = repo.loadLineParsModel(baseName);
    return successResponse(requestId, model);
  }

  private McpQuery1cResponse handleGetFullParsModel(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    ModelRepository repo = createRepoForParameter(parameterName);
    FullParsModel model = repo.loadFullParsModel(baseName);
    return successResponse(requestId, model);
  }

  private McpQuery1cResponse handleGetHierarchy(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    ModelRepository repo = createRepoForParameter(parameterName);
    List<HierarchyNode> hierarchy = repo.loadHierarchy(baseName);
    return successResponse(requestId, hierarchy);
  }

  private McpQuery1cResponse handleGetFieldLineage(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    String alias = getString(args, "alias");
    if (alias == null) {
      throw new SdqlApiException("MISSING_INPUT", "alias is required");
    }
    ModelRepository repo = createRepoForParameter(parameterName);
    FullParsModel model = repo.loadFullParsModel(baseName);
    FullParsNode node = resolveNode(model, args);

    FieldLineageNode lineage = repo.loadFieldLineage(baseName, node.getId(), alias);
    if (lineage == null) {
      lineage = buildFieldLineage(repo, baseName, node.getId(), alias);
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("parameterName", parameterName);
    response.put("baseName", baseName);
    response.put("nodeId", node.getId());
    response.put("nodeName", node.getName());
    response.put("alias", alias);
    response.put("lineage", lineage);
    return successResponse(requestId, response);
  }

  private McpQuery1cResponse handleGetFullFieldLineage(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    List<String> aliases = getStringList(args, "aliases");
    if (aliases == null || aliases.isEmpty()) {
      throw new SdqlApiException("MISSING_INPUT", "aliases is required");
    }
    ModelRepository repo = createRepoForParameter(parameterName);
    FullParsModel model = repo.loadFullParsModel(baseName);
    FullParsNode node = resolveNode(model, args);

    FflBuilder builder = new FflBuilder();
    List<FullParsNode> fflNodes = builder.build(model, node.getId(), aliases);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("parameterName", parameterName);
    response.put("baseName", baseName);
    response.put("nodeId", node.getId());
    response.put("nodeName", node.getName());
    response.put("aliases", aliases);
    response.put("fflNodes", fflNodes);
    return successResponse(requestId, response);
  }

  private McpQuery1cResponse handleGetRestoredQuery(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    List<String> aliases = getStringList(args, "aliases");
    if (aliases == null || aliases.isEmpty()) {
      throw new SdqlApiException("MISSING_INPUT", "aliases is required");
    }
    String format = getString(args, "format", "sql");

    ModelRepository repo = createRepoForParameter(parameterName);
    FullParsModel model = repo.loadFullParsModel(baseName);
    FullParsNode node = resolveNode(model, args);

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
      response.put("parameterName", parameterName);
      response.put("baseName", baseName);
      response.put("nodeId", node.getId());
      response.put("nodeName", node.getName());
      response.put("aliases", aliases);
      response.put("sql", sql);
      return successResponse(requestId, response);
    }
  }

  private McpQuery1cResponse handleGetNodeInfo(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    String modelType = getString(args, "modelType", "full_pars");
    ModelRepository repo = createRepoForParameter(parameterName);

    FullParsNode node;
    switch (modelType) {
      case "sdbl" -> {
        QueryModel model = repo.loadSdblModel(baseName);
        node = resolveNodeFromQueryModel(model, args);
      }
      case "line_pars" -> {
        LineParsModel model = repo.loadLineParsModel(baseName);
        node = resolveNodeFromLineParsModel(model, args);
      }
      default -> {
        FullParsModel model = repo.loadFullParsModel(baseName);
        node = resolveNode(model, args);
      }
    }
    return successResponse(requestId, node);
  }

  private McpQuery1cResponse handleListNodes(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    String modelType = getString(args, "modelType", "full_pars");
    String nodeTypeFilter = getString(args, "nodeType");
    ModelRepository repo = createRepoForParameter(parameterName);

    List<Map<String, Object>> nodes = new ArrayList<>();

    switch (modelType) {
      case "sdbl" -> {
        QueryModel model = repo.loadSdblModel(baseName);
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
        LineParsModel model = repo.loadLineParsModel(baseName);
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
        FullParsModel model = repo.loadFullParsModel(baseName);
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

  private McpQuery1cResponse handleGetVerificationReport(JsonNode args, String requestId) {
    String parameterName = getString(args, "parameterName");
    String baseName = resolveBaseName(parameterName);
    ModelRepository repo = createRepoForParameter(parameterName);
    VerificationReport report;
    try {
      report = repo.loadVerificationReport(baseName);
    } catch (Exception e) {
      report = new VerificationReport();
      report.setBaseName(baseName);
      report.setTotalNodes(0);
      report.setMatched(0);
      report.setMismatched(0);
    }
    return successResponse(requestId, report);
  }

  // --- Helpers ---

  private String resolveBaseName(String parameterName) {
    if (parameterName == null || parameterName.isBlank()) {
      throw new SdqlApiException("MISSING_INPUT", "parameterName is required");
    }
    Optional<QueryParameter> opt = store.findByName(parameterName);
    if (opt.isEmpty()) {
      throw new SdqlApiException("NOT_FOUND", "Parameter not found: " + parameterName);
    }
    return opt.get().getBaseName();
  }

  private ModelRepository createRepoForParameter(String parameterName) {
    Path paramDir = store.getParameterDir(parameterName);
    return new FileSystemModelRepository(paramDir);
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
      throw new com.github._1c_syntax.bsl.parser.sdql.api.NodeNotFoundException("Node not found: id=" + nodeId);
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
      throw new com.github._1c_syntax.bsl.parser.sdql.api.NodeNotFoundException("Node not found: name=" + nodeName);
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
      throw new com.github._1c_syntax.bsl.parser.sdql.api.NodeNotFoundException("Node not found: id=" + nodeId);
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
      throw new com.github._1c_syntax.bsl.parser.sdql.api.NodeNotFoundException("Node not found: name=" + nodeName);
    }
    throw new SdqlApiException("MISSING_NODE", "Either nodeId or nodeName must be provided");
  }

  private FieldLineageNode buildFieldLineage(ModelRepository repo, String baseName, int nodeId, String alias) {
    try {
      LineParsModel lineParsModel = repo.loadLineParsModel(baseName);
      List<HierarchyNode> hierarchy = repo.loadHierarchy(baseName);
      if (lineParsModel == null || hierarchy == null || hierarchy.isEmpty()) {
        FieldLineageNode node = new FieldLineageNode();
        node.setId(nodeId);
        node.setAlias(alias);
        return node;
      }
      com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsFieldExtractor extractor =
        new com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsFieldExtractor(lineParsModel, hierarchy);
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

  private McpQuery1cResponse successResponse(String requestId, Object data) {
    McpQuery1cResponse response = new McpQuery1cResponse();
    response.setJsonrpc("2.0");
    response.setId(requestId);
    try {
      String json = MAPPER.writeValueAsString(data);
      response.setResult(McpQuery1cResponse.McpResult.text(json));
    } catch (Exception e) {
      response.setResult(McpQuery1cResponse.McpResult.text("{}"));
    }
    return response;
  }

  private McpQuery1cResponse errorResponse(String requestId, int code, String errorCode, String message) {
    McpQuery1cResponse response = new McpQuery1cResponse();
    response.setJsonrpc("2.0");
    response.setId(requestId);
    McpQuery1cResponse.McpError error = new McpQuery1cResponse.McpError();
    error.setCode(code);
    error.setMessage(errorCode + ": " + message);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("details", message);
    error.setData(data);
    response.setError(error);
    return response;
  }
}
