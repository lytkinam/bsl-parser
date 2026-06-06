package com.github._1c_syntax.bsl.parser.sdql.api;

import java.nio.file.Path;

/**
 * Unified entry point for running the SDQL pipeline.
 * Does not depend on MCP — can be used from any context.
 */
public interface SdqlPipeline {

  /**
   * Run the full pipeline for SQL text.
   * Result is saved to the specified directory.
   *
   * @param sqlText   SQL package text
   * @param outputDir Directory for artifacts
   * @param baseName  Base name (if null — auto-generated)
   * @param detailed  Generate markdown reports
   * @return PipelineResult with meta-information
   */
  PipelineResult analyze(String sqlText, Path outputDir, String baseName, boolean detailed);

  /**
   * Run the full pipeline for SQL text.
   * Returns result in memory (without writing to files).
   *
   * @param sqlText  SQL package text
   * @param baseName Base name (if null — auto-generated)
   * @return PipelineResult with models in memory
   */
  PipelineResult analyzeInMemory(String sqlText, String baseName);
}
