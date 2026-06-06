package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.FieldLineageNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.HierarchyNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import com.github._1c_syntax.bsl.parser.sdql.query_extraction.VerificationReport;

import java.util.List;

/**
 * Abstraction over model storage.
 * FileSystem implementation — FileSystemModelRepository.
 * Possible in-memory implementation (tests) or DB.
 */
public interface ModelRepository {

  void saveSdblModel(String baseName, QueryModel model);
  QueryModel loadSdblModel(String baseName);

  void saveLineParsModel(String baseName, LineParsModel model);
  LineParsModel loadLineParsModel(String baseName);

  void saveFullParsModel(String baseName, FullParsModel model);
  FullParsModel loadFullParsModel(String baseName);

  void saveHierarchy(String baseName, List<HierarchyNode> hierarchy);
  List<HierarchyNode> loadHierarchy(String baseName);

  void saveFieldLineage(String baseName, int nodeId, String alias, FieldLineageNode lineage);
  FieldLineageNode loadFieldLineage(String baseName, int nodeId, String alias);

  void saveFullFieldLineage(String baseName, int nodeId, String nodeName,
                            List<String> aliases, List<FullParsNode> fflNodes);
  List<FullParsNode> loadFullFieldLineage(String baseName, int nodeId, List<String> aliases);

  void saveRestoredQuery(String baseName, int nodeId, String alias, String sql);
  String loadRestoredQuery(String baseName, int nodeId, String alias);

  void saveVerificationReport(String baseName, VerificationReport report);
  VerificationReport loadVerificationReport(String baseName);

  boolean exists(String baseName);
  void delete(String baseName);
  List<String> listBaseNames();
}
