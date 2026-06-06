package com.github._1c_syntax.bsl.parser.sdql.mcp;

import lombok.Data;

import java.nio.file.Path;

@Data
public class PipelineContext {
  private final String baseName;
  private final Path tempDir;
  private final boolean online;
}
