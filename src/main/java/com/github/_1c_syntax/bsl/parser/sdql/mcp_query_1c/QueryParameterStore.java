package com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Persistent store for query parameters and their artifacts.
 * <p>
 * Storage layout:
 * <pre>
 * storageDir/
 *   parameters.json          -- registry of all parameters
 *   &lt;sanitized_name&gt;/
 *     query.sql              -- original SQL text
 *     parameter.json         -- metadata
 *     artifacts/             -- SDQL analysis artifacts
 * </pre>
 */
public class QueryParameterStore {

  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

  private final Path storageDir;
  private final Path registryFile;

  public QueryParameterStore(Path storageDir) {
    this.storageDir = storageDir;
    this.registryFile = storageDir.resolve("parameters.json");
    try {
      Files.createDirectories(storageDir);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create storage directory: " + storageDir, e);
    }
  }

  /**
   * Sanitize parameter name for use as filesystem directory name.
   */
  public static String sanitizeName(String name) {
    return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
  }

  /**
   * Save or update a parameter. If it already exists, overwrites.
   */
  public synchronized void save(QueryParameter parameter) {
    try {
      String sanitized = sanitizeName(parameter.getName());
      parameter.setSanitizedName(sanitized);

      Path paramDir = storageDir.resolve(sanitized);
      Files.createDirectories(paramDir);

      // Save SQL text
      Files.writeString(paramDir.resolve("query.sql"), parameter.getSqlText());

      // Save metadata
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(
        paramDir.resolve("parameter.json").toFile(), parameter);

      // Update registry
      List<QueryParameter> registry = loadRegistry();
      registry.removeIf(p -> p.getName().equals(parameter.getName()));
      registry.add(parameter);
      saveRegistry(registry);

    } catch (IOException e) {
      throw new RuntimeException("Failed to save parameter: " + parameter.getName(), e);
    }
  }

  /**
   * Find parameter by exact name.
   */
  public synchronized Optional<QueryParameter> findByName(String name) {
    return loadRegistry().stream()
      .filter(p -> p.getName().equals(name))
      .findFirst();
  }

  /**
   * Get the artifacts directory for a parameter.
   * This is the SDBL_PARS subdirectory used by the pipeline as the analysis root.
   */
  public Path getArtifactsDir(String name) {
    String sanitized = sanitizeName(name);
    return storageDir.resolve(sanitized).resolve("SDBL_PARS");
  }

  /**
   * Get the parameter base directory (parent of SDBL_PARS).
   */
  public Path getParameterDir(String name) {
    String sanitized = sanitizeName(name);
    return storageDir.resolve(sanitized);
  }

  /**
   * Get the SQL file path for a parameter.
   */
  public Path getQueryFile(String name) {
    String sanitized = sanitizeName(name);
    return storageDir.resolve(sanitized).resolve("query.sql");
  }

  /**
   * List all parameters, optionally filtered by name mask.
   */
  public synchronized List<QueryParameter> listAll(String filterMask) {
    List<QueryParameter> all = loadRegistry();
    if (filterMask == null || filterMask.isBlank()) {
      return all;
    }
    String lowerMask = filterMask.toLowerCase();
    return all.stream()
      .filter(p -> p.getName().toLowerCase().contains(lowerMask))
      .collect(Collectors.toList());
  }

  /**
   * Delete a parameter and its artifacts.
   */
  public synchronized void delete(String name) {
    try {
      String sanitized = sanitizeName(name);
      Path paramDir = storageDir.resolve(sanitized);
      if (Files.exists(paramDir)) {
        deleteDirectory(paramDir);
      }
      List<QueryParameter> registry = loadRegistry();
      registry.removeIf(p -> p.getName().equals(name));
      saveRegistry(registry);
    } catch (IOException e) {
      throw new RuntimeException("Failed to delete parameter: " + name, e);
    }
  }

  private List<QueryParameter> loadRegistry() {
    if (!Files.exists(registryFile)) {
      return new ArrayList<>();
    }
    try {
      return MAPPER.readValue(registryFile.toFile(), new TypeReference<List<QueryParameter>>() {});
    } catch (IOException e) {
      System.err.println("Warning: failed to load registry, starting fresh: " + e.getMessage());
      return new ArrayList<>();
    }
  }

  private void saveRegistry(List<QueryParameter> registry) throws IOException {
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(registryFile.toFile(), registry);
  }

  private void deleteDirectory(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.delete(p);
        } catch (IOException e) {
          // ignore
        }
      });
    }
  }
}
