package com.github._1c_syntax.bsl.parser.sdql.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.FieldLineageNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.HierarchyNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import com.github._1c_syntax.bsl.parser.sdql.query_extraction.VerificationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileSystemModelRepository implements ModelRepository {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path baseDir;

  public FileSystemModelRepository(Path baseDir) {
    this.baseDir = baseDir;
  }

  public Path getBaseDir() {
    return baseDir;
  }

  private Path sdblParsDir() {
    return baseDir.resolve("SDBL_PARS");
  }

  private Path lineParsDir() {
    return baseDir.resolve("LINE_PARS");
  }

  private Path fullParsDir() {
    return baseDir.resolve("FULL_PARS");
  }

  private Path fieldLineageDir() {
    return baseDir.resolve("field_lineage");
  }

  private Path fullFieldLineageDir() {
    return baseDir.resolve("full_field_lineage");
  }

  private Path restoredQueriesDir() {
    return baseDir.resolve("RESTORED_QUERIES");
  }

  private Path extractedQueriesDir() {
    return baseDir.resolve("EXTRACTED_QUERIES");
  }

  private Path verificationDir() {
    return baseDir.resolve("VERIFICATION");
  }

  @Override
  public void saveSdblModel(String baseName, QueryModel model) {
    try {
      Files.createDirectories(sdblParsDir());
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(
        sdblParsDir().resolve("sdbl_parse_model_" + baseName + ".json").toFile(), model);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to save SDBL model", e);
    }
  }

  @Override
  public QueryModel loadSdblModel(String baseName) {
    try {
      return MAPPER.readValue(
        sdblParsDir().resolve("sdbl_parse_model_" + baseName + ".json").toFile(),
        QueryModel.class);
    } catch (IOException e) {
      throw new SdqlApiException("ARTIFACT_NOT_FOUND", "SDBL model not found: " + baseName, e);
    }
  }

  @Override
  public void saveLineParsModel(String baseName, LineParsModel model) {
    try {
      Files.createDirectories(lineParsDir());
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(
        lineParsDir().resolve("LINE_PARS_model_" + baseName + ".json").toFile(), model);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to save LINE_PARS model", e);
    }
  }

  @Override
  public LineParsModel loadLineParsModel(String baseName) {
    try {
      return MAPPER.readValue(
        lineParsDir().resolve("LINE_PARS_model_" + baseName + ".json").toFile(),
        LineParsModel.class);
    } catch (IOException e) {
      throw new SdqlApiException("ARTIFACT_NOT_FOUND", "LINE_PARS model not found: " + baseName, e);
    }
  }

  @Override
  public void saveFullParsModel(String baseName, FullParsModel model) {
    try {
      Files.createDirectories(fullParsDir());
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(
        fullParsDir().resolve("FULL_PARS_model_" + baseName + ".json").toFile(), model);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to save FULL_PARS model", e);
    }
  }

  @Override
  public FullParsModel loadFullParsModel(String baseName) {
    try {
      return MAPPER.readValue(
        fullParsDir().resolve("FULL_PARS_model_" + baseName + ".json").toFile(),
        FullParsModel.class);
    } catch (IOException e) {
      throw new SdqlApiException("ARTIFACT_NOT_FOUND", "FULL_PARS model not found: " + baseName, e);
    }
  }

  @Override
  public void saveHierarchy(String baseName, List<HierarchyNode> hierarchy) {
    try {
      Files.createDirectories(lineParsDir());
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(
        lineParsDir().resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(), hierarchy);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to save hierarchy", e);
    }
  }

  @Override
  public List<HierarchyNode> loadHierarchy(String baseName) {
    try {
      return MAPPER.readValue(
        lineParsDir().resolve("LINE_PARS_hierarchy_" + baseName + ".json").toFile(),
        new TypeReference<List<HierarchyNode>>() {});
    } catch (IOException e) {
      throw new SdqlApiException("ARTIFACT_NOT_FOUND", "Hierarchy not found: " + baseName, e);
    }
  }

  @Override
  public void saveFieldLineage(String baseName, int nodeId, String alias, FieldLineageNode lineage) {
    try {
      Path dir = fieldLineageDir().resolve(baseName).resolve(nodeId + "_" + alias);
      Files.createDirectories(dir);
      String fileName = "FLS_" + baseName + "_" + nodeId + "_" + alias + "_" + alias + ".json";
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(
        dir.resolve(fileName).toFile(), lineage);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to save field lineage", e);
    }
  }

  @Override
  public FieldLineageNode loadFieldLineage(String baseName, int nodeId, String alias) {
    try {
      Path baseDir = fieldLineageDir().resolve(baseName);
      if (!Files.exists(baseDir)) {
        return null;
      }
      // Find node directory that starts with nodeId_
      Path nodeDir = null;
      try (var stream = Files.list(baseDir)) {
        nodeDir = stream.filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().startsWith(nodeId + "_"))
          .findFirst()
          .orElse(null);
      }
      if (nodeDir == null) {
        return null;
      }
      String fileName = "FLS_" + baseName + "_" + nodeId + "_"
        + nodeDir.getFileName().toString().substring(nodeDir.getFileName().toString().indexOf('_') + 1)
        + "_" + alias + ".json";
      Path file = nodeDir.resolve(fileName);
      if (!Files.exists(file)) {
        return null;
      }
      return MAPPER.readValue(file.toFile(), FieldLineageNode.class);
    } catch (IOException e) {
      return null;
    }
  }

  @Override
  public void saveFullFieldLineage(String baseName, int nodeId, String nodeName,
                                   List<String> aliases, List<FullParsNode> fflNodes) {
    try {
      Path dir = fullFieldLineageDir().resolve(baseName).resolve(nodeId + "_" + nodeName);
      Files.createDirectories(dir);
      String fileName = "FFL_" + baseName + "_" + nodeId + "_" + nodeName + "_"
        + String.join("_", aliases) + ".json";
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(
        dir.resolve(fileName).toFile(), fflNodes);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to save full field lineage", e);
    }
  }

  @Override
  public List<FullParsNode> loadFullFieldLineage(String baseName, int nodeId, List<String> aliases) {
    try {
      Path dir = fullFieldLineageDir().resolve(baseName);
      // Find matching directory
      try (Stream<Path> dirs = Files.list(dir)) {
        Path nodeDir = dirs.filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().startsWith(nodeId + "_"))
          .findFirst()
          .orElseThrow(() -> new IOException("Node dir not found"));
        String fileName = "FFL_" + baseName + "_" + nodeId + "_"
          + nodeDir.getFileName().toString().substring(nodeDir.getFileName().toString().indexOf('_') + 1)
          + "_" + String.join("_", aliases) + ".json";
        return MAPPER.readValue(nodeDir.resolve(fileName).toFile(),
          new TypeReference<List<FullParsNode>>() {});
      }
    } catch (IOException e) {
      throw new SdqlApiException("ARTIFACT_NOT_FOUND", "Full field lineage not found", e);
    }
  }

  @Override
  public void saveRestoredQuery(String baseName, int nodeId, String alias, String sql) {
    try {
      Path dir = restoredQueriesDir().resolve(baseName).resolve(nodeId + "_" + alias);
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(alias + ".sql"), sql);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to save restored query", e);
    }
  }

  @Override
  public String loadRestoredQuery(String baseName, int nodeId, String alias) {
    try {
      Path dir = restoredQueriesDir().resolve(baseName).resolve(nodeId + "_" + alias);
      return Files.readString(dir.resolve(alias + ".sql"));
    } catch (IOException e) {
      throw new SdqlApiException("ARTIFACT_NOT_FOUND", "Restored query not found", e);
    }
  }

  @Override
  public void saveVerificationReport(String baseName, VerificationReport report) {
    try {
      Files.createDirectories(verificationDir().resolve(baseName));
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(
        verificationDir().resolve(baseName).resolve("verification_report.json").toFile(), report);
    } catch (IOException e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to save verification report", e);
    }
  }

  @Override
  public VerificationReport loadVerificationReport(String baseName) {
    try {
      return MAPPER.readValue(
        verificationDir().resolve(baseName).resolve("verification_report.json").toFile(),
        VerificationReport.class);
    } catch (IOException e) {
      throw new SdqlApiException("ARTIFACT_NOT_FOUND", "Verification report not found: " + baseName, e);
    }
  }

  @Override
  public boolean exists(String baseName) {
    return Files.exists(sdblParsDir().resolve("sdbl_parse_model_" + baseName + ".json"));
  }

  @Override
  public void delete(String baseName) {
    // Not implemented for file system
  }

  @Override
  public List<String> listBaseNames() {
    try {
      if (!Files.exists(sdblParsDir())) {
        return List.of();
      }
      return Files.list(sdblParsDir())
        .filter(p -> p.getFileName().toString().startsWith("sdbl_parse_model_"))
        .map(p -> {
          String name = p.getFileName().toString();
          name = name.substring("sdbl_parse_model_".length());
          if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
          }
          return name;
        })
        .collect(Collectors.toList());
    } catch (IOException e) {
      return List.of();
    }
  }
}
