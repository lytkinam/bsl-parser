package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.SdqlQueryPackageAnalyzer;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullFieldLineageBuilder;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModelBuilder;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.*;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import com.github._1c_syntax.bsl.parser.sdql.query_extraction.QueryExtractor;
import com.github._1c_syntax.bsl.parser.sdql.query_extraction.VerificationReport;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.QueryReconstructor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class SdqlPipelineImpl implements SdqlPipeline {

  private final ModelRepository repository;

  public SdqlPipelineImpl(ModelRepository repository) {
    this.repository = repository;
  }

  @Override
  public PipelineResult analyze(String sqlText, Path outputDir, String baseName, boolean detailed) {
    String actualBaseName = baseName != null ? baseName : generateBaseName();
    Path actualOutputDir = outputDir != null ? outputDir : Path.of(System.getProperty("java.io.tmpdir"), "sdql-" + actualBaseName);
    try {
      Files.createDirectories(actualOutputDir);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to create output directory", e);
    }

    // 1. Primary analysis
    SdqlQueryPackageAnalyzer analyzer = new SdqlQueryPackageAnalyzer();
    try {
      File tempFile = File.createTempFile("sdql_", ".sql");
      Files.writeString(tempFile.toPath(), sqlText);
      analyzer.analyze(tempFile, actualOutputDir.toFile(), actualBaseName);
      tempFile.delete();
    } catch (Exception e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to analyze SQL", e);
    }

    QueryModel sdblModel = analyzer.getModel();
    repository.saveSdblModel(actualBaseName, sdblModel);

    // 2. LINE_PARS model
    LineParsModelBuilder lineParsBuilder = new LineParsModelBuilder();
    Path lineParsDir;
    try {
      lineParsDir = lineParsBuilder.build(actualOutputDir, actualBaseName);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to build LINE_PARS model", e);
    }
    LineParsModel lineParsModel = loadLineParsModel(lineParsDir, actualBaseName);
    repository.saveLineParsModel(actualBaseName, lineParsModel);

    // 3. Hierarchy
    LineParsHierarchyBuilder hierarchyBuilder = new LineParsHierarchyBuilder();
    try {
      hierarchyBuilder.build(lineParsDir, actualBaseName);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to build hierarchy", e);
    }
    List<HierarchyNode> hierarchy = loadHierarchy(lineParsDir, actualBaseName);
    repository.saveHierarchy(actualBaseName, hierarchy);

    // 4. LINE_PARS field lineage
    LineParsFieldLineageBuilder fieldLineageBuilder = new LineParsFieldLineageBuilder();
    try {
      fieldLineageBuilder.build(actualOutputDir, actualBaseName);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to build field lineage", e);
    }

    // 5. FULL_PARS model
    FullParsModelBuilder fullParsBuilder = new FullParsModelBuilder();
    Path fullParsDir;
    try {
      fullParsDir = fullParsBuilder.build(lineParsDir, actualBaseName);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to build FULL_PARS model", e);
    }
    FullParsModel fullParsModel = loadFullParsModel(fullParsDir, actualBaseName);
    repository.saveFullParsModel(actualBaseName, fullParsModel);

    // 6. Full field lineage
    FullFieldLineageBuilder fflBuilder = new FullFieldLineageBuilder();
    try {
      fflBuilder.build(fullParsDir, actualBaseName);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to build full field lineage", e);
    }

    // Generate Markdown reports from JSON artifacts
    try {
      new com.github._1c_syntax.bsl.parser.sdql.md.SdqlModelMdBuilder().build(actualOutputDir, actualBaseName);
      new com.github._1c_syntax.bsl.parser.sdql.md.LineParsModelMdBuilder().build(lineParsDir, actualBaseName);
      new com.github._1c_syntax.bsl.parser.sdql.md.HierarchyMdBuilder().build(lineParsDir, actualBaseName);

      java.nio.file.Path fieldLineageDir = actualOutputDir.getParent().resolve("field_lineage");
      new com.github._1c_syntax.bsl.parser.sdql.md.LineParsFieldLineageMdBuilder().build(fieldLineageDir, actualBaseName);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to generate markdown reports", e);
    }

    // 7. Restored queries
    QueryReconstructor reconstructor = new QueryReconstructor();
    try {
      reconstructor.build(fullParsDir, actualBaseName);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to reconstruct queries", e);
    }

    // 8. Extract queries + verification
    QueryExtractor extractor = new QueryExtractor();
    try {
      extractor.build(fullParsDir, actualBaseName);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to extract queries", e);
    }

    PipelineResult result = new PipelineResult();
    result.setBaseName(actualBaseName);
    result.setNodesCount(fullParsModel.getNodes().size());
    result.setTargetNodes(findTargetNodes(fullParsModel));
    result.setSdblModel(sdblModel);
    result.setLineParsModel(lineParsModel);
    result.setFullParsModel(fullParsModel);
    result.setSdblParsPath(actualOutputDir.resolve("SDBL_PARS").resolve("sdbl_parse_model_" + actualBaseName + ".json"));
    result.setLineParsPath(lineParsDir.resolve("LINE_PARS_model_" + actualBaseName + ".json"));
    result.setFullParsPath(fullParsDir.resolve("FULL_PARS_model_" + actualBaseName + ".json"));
    result.setHierarchyPath(lineParsDir.resolve("LINE_PARS_hierarchy_" + actualBaseName + ".json"));
    result.setFieldLineagePath(actualOutputDir.resolve("field_lineage").resolve(actualBaseName));
    result.setFullFieldLineagePath(actualOutputDir.resolve("full_field_lineage").resolve(actualBaseName));
    result.setRestoredQueriesPath(actualOutputDir.resolve("RESTORED_QUERIES").resolve(actualBaseName));
    result.setExtractedQueriesPath(actualOutputDir.resolve("EXTRACTED_QUERIES").resolve(actualBaseName));
    result.setVerificationPath(actualOutputDir.resolve("VERIFICATION").resolve(actualBaseName).resolve("verification_report.json"));

    return result;
  }

  @Override
  public PipelineResult analyzeInMemory(String sqlText, String baseName) {
    String actualBaseName = baseName != null ? baseName : generateBaseName();

    // 1. Primary analysis in memory
    SdqlQueryPackageAnalyzer analyzer = new SdqlQueryPackageAnalyzer();
    try {
      File tempFile = File.createTempFile("sdql_", ".sql");
      Files.writeString(tempFile.toPath(), sqlText);
      analyzer.analyze(tempFile, tempFile.getParentFile(), actualBaseName);
      tempFile.delete();
    } catch (Exception e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to analyze SQL", e);
    }

    QueryModel sdblModel = analyzer.getModel();
    repository.saveSdblModel(actualBaseName, sdblModel);

    // 2. LINE_PARS model in memory
    LineParsModel lineParsModel = buildLineParsModelInMemory(sdblModel, actualBaseName);
    repository.saveLineParsModel(actualBaseName, lineParsModel);

    // 3. Hierarchy in memory
    List<HierarchyNode> hierarchy = buildHierarchyInMemory(lineParsModel);
    repository.saveHierarchy(actualBaseName, hierarchy);

    // 4. FULL_PARS model in memory
    FullParsModel fullParsModel = buildFullParsModelInMemory(lineParsModel, hierarchy, actualBaseName);
    repository.saveFullParsModel(actualBaseName, fullParsModel);

    PipelineResult result = new PipelineResult();
    result.setBaseName(actualBaseName);
    result.setNodesCount(fullParsModel.getNodes().size());
    result.setTargetNodes(findTargetNodes(fullParsModel));
    result.setSdblModel(sdblModel);
    result.setLineParsModel(lineParsModel);
    result.setFullParsModel(fullParsModel);

    return result;
  }

  private LineParsModel buildLineParsModelInMemory(QueryModel sdblModel, String baseName) {
    // Use a temporary directory for the builder, then load the result
    try {
      Path tempDir = Files.createTempDirectory("sdql_linepars_");
      Files.createDirectories(tempDir.resolve("SDBL_PARS"));
      com.github._1c_syntax.bsl.parser.sdql.io.ModelJsonMapper.write(
        sdblModel, tempDir.resolve("SDBL_PARS").resolve("sdbl_parse_model_" + baseName + ".json"));
      LineParsModelBuilder builder = new LineParsModelBuilder();
      Path lineParsDir = builder.build(tempDir.resolve("SDBL_PARS"), baseName);
      LineParsModel model = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
        lineParsDir.resolve("LINE_PARS_model_" + baseName + ".json").toFile(),
        LineParsModel.class);
      deleteDirectory(tempDir);
      return model;
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to build LINE_PARS model in memory", e);
    }
  }

  private List<HierarchyNode> buildHierarchyInMemory(LineParsModel lineParsModel) {
    try {
      Path tempDir = Files.createTempDirectory("sdql_hierarchy_");
      Files.createDirectories(tempDir.resolve("LINE_PARS"));
      new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
        tempDir.resolve("LINE_PARS").resolve("LINE_PARS_model_tmp.json").toFile(), lineParsModel);
      LineParsHierarchyBuilder builder = new LineParsHierarchyBuilder();
      builder.build(tempDir.resolve("LINE_PARS"), "tmp");
      List<HierarchyNode> hierarchy = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
        tempDir.resolve("LINE_PARS").resolve("LINE_PARS_hierarchy_tmp.json").toFile(),
        new com.fasterxml.jackson.core.type.TypeReference<List<HierarchyNode>>() {});
      deleteDirectory(tempDir);
      return hierarchy;
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to build hierarchy in memory", e);
    }
  }

  private FullParsModel buildFullParsModelInMemory(LineParsModel lineParsModel, List<HierarchyNode> hierarchy, String baseName) {
    try {
      Path tempDir = Files.createTempDirectory("sdql_fullpars_");
      Files.createDirectories(tempDir.resolve("LINE_PARS"));
      new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
        tempDir.resolve("LINE_PARS").resolve("LINE_PARS_model_" + baseName + ".json").toFile(), lineParsModel);
      new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
        tempDir.resolve("LINE_PARS").resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(), hierarchy);
      FullParsModelBuilder builder = new FullParsModelBuilder();
      Path fullParsDir = builder.build(tempDir.resolve("LINE_PARS"), baseName);
      FullParsModel model = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
        fullParsDir.resolve("FULL_PARS_model_" + baseName + ".json").toFile(),
        FullParsModel.class);
      deleteDirectory(tempDir);
      return model;
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to build FULL_PARS model in memory", e);
    }
  }

  private LineParsModel loadLineParsModel(Path lineParsDir, String baseName) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
        lineParsDir.resolve("LINE_PARS_model_" + baseName + ".json").toFile(),
        LineParsModel.class);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to load LINE_PARS model", e);
    }
  }

  private List<HierarchyNode> loadHierarchy(Path lineParsDir, String baseName) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
        lineParsDir.resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(),
        new com.fasterxml.jackson.core.type.TypeReference<List<HierarchyNode>>() {});
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to load hierarchy", e);
    }
  }

  private FullParsModel loadFullParsModel(Path fullParsDir, String baseName) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
        fullParsDir.resolve("FULL_PARS_model_" + baseName + ".json").toFile(),
        FullParsModel.class);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to load FULL_PARS model", e);
    }
  }

  private List<TargetNodeInfo> findTargetNodes(FullParsModel model) {
    List<FullParsNode> resultNodes = model.getNodes().stream()
      .filter(n -> "result".equals(n.getType()))
      .collect(Collectors.toList());

    if (!resultNodes.isEmpty()) {
      return resultNodes.stream().map(this::toTargetNodeInfo).collect(Collectors.toList());
    }

    return model.getNodes().stream()
      .filter(n -> "temp_query".equals(n.getType()))
      .max(Comparator.comparingInt(FullParsNode::getId))
      .map(n -> List.of(toTargetNodeInfo(n)))
      .orElse(List.of());
  }

  private TargetNodeInfo toTargetNodeInfo(FullParsNode node) {
    TargetNodeInfo info = new TargetNodeInfo();
    info.setId(node.getId());
    info.setName(node.getName());
    info.setType(node.getType());
    return info;
  }

  private String generateBaseName() {
    return "tmp_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private void deleteDirectory(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    Files.walk(dir)
      .sorted(Comparator.reverseOrder())
      .forEach(p -> {
        try {
          Files.delete(p);
        } catch (IOException e) {
          // ignore
        }
      });
  }
}
