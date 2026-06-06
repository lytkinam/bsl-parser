package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import lombok.Data;

import java.nio.file.Path;
import java.util.List;

@Data
public class PipelineResult {
  private String baseName;
  private int nodesCount;
  private List<TargetNodeInfo> targetNodes;

  // Models in memory (for analyzeInMemory)
  private QueryModel sdblModel;
  private LineParsModel lineParsModel;
  private FullParsModel fullParsModel;

  // Paths to artifacts (for analyze)
  private Path sdblParsPath;
  private Path lineParsPath;
  private Path fullParsPath;
  private Path hierarchyPath;
  private Path fieldLineagePath;
  private Path fullFieldLineagePath;
  private Path restoredQueriesPath;
  private Path extractedQueriesPath;
  private Path verificationPath;
}
