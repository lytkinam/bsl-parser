package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.FieldLineageNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.HierarchyNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import com.github._1c_syntax.bsl.parser.sdql.query_extraction.VerificationReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemoryModelRepository implements ModelRepository {

  private final Map<String, QueryModel> sdblModels = new HashMap<>();
  private final Map<String, LineParsModel> lineParsModels = new HashMap<>();
  private final Map<String, FullParsModel> fullParsModels = new HashMap<>();
  private final Map<String, List<HierarchyNode>> hierarchies = new HashMap<>();
  private final Map<String, FieldLineageNode> fieldLineages = new HashMap<>();
  private final Map<String, List<FullParsNode>> fullFieldLineages = new HashMap<>();
  private final Map<String, String> restoredQueries = new HashMap<>();
  private final Map<String, VerificationReport> verificationReports = new HashMap<>();

  private String key(String baseName, int nodeId, String alias) {
    return baseName + ":" + nodeId + ":" + alias;
  }

  private String key(String baseName, int nodeId, List<String> aliases) {
    return baseName + ":" + nodeId + ":" + String.join("_", aliases);
  }

  @Override
  public void saveSdblModel(String baseName, QueryModel model) {
    sdblModels.put(baseName, model);
  }

  @Override
  public QueryModel loadSdblModel(String baseName) {
    return sdblModels.get(baseName);
  }

  @Override
  public void saveLineParsModel(String baseName, LineParsModel model) {
    lineParsModels.put(baseName, model);
  }

  @Override
  public LineParsModel loadLineParsModel(String baseName) {
    return lineParsModels.get(baseName);
  }

  @Override
  public void saveFullParsModel(String baseName, FullParsModel model) {
    fullParsModels.put(baseName, model);
  }

  @Override
  public FullParsModel loadFullParsModel(String baseName) {
    return fullParsModels.get(baseName);
  }

  @Override
  public void saveHierarchy(String baseName, List<HierarchyNode> hierarchy) {
    hierarchies.put(baseName, hierarchy);
  }

  @Override
  public List<HierarchyNode> loadHierarchy(String baseName) {
    return hierarchies.getOrDefault(baseName, List.of());
  }

  @Override
  public void saveFieldLineage(String baseName, int nodeId, String alias, FieldLineageNode lineage) {
    fieldLineages.put(key(baseName, nodeId, alias), lineage);
  }

  @Override
  public FieldLineageNode loadFieldLineage(String baseName, int nodeId, String alias) {
    return fieldLineages.get(key(baseName, nodeId, alias));
  }

  @Override
  public void saveFullFieldLineage(String baseName, int nodeId, String nodeName,
                                   List<String> aliases, List<FullParsNode> fflNodes) {
    fullFieldLineages.put(key(baseName, nodeId, aliases), fflNodes);
  }

  @Override
  public List<FullParsNode> loadFullFieldLineage(String baseName, int nodeId, List<String> aliases) {
    return fullFieldLineages.getOrDefault(key(baseName, nodeId, aliases), List.of());
  }

  @Override
  public void saveRestoredQuery(String baseName, int nodeId, String alias, String sql) {
    restoredQueries.put(key(baseName, nodeId, alias), sql);
  }

  @Override
  public String loadRestoredQuery(String baseName, int nodeId, String alias) {
    return restoredQueries.get(key(baseName, nodeId, alias));
  }

  @Override
  public void saveVerificationReport(String baseName, VerificationReport report) {
    verificationReports.put(baseName, report);
  }

  @Override
  public VerificationReport loadVerificationReport(String baseName) {
    return verificationReports.get(baseName);
  }

  @Override
  public boolean exists(String baseName) {
    return sdblModels.containsKey(baseName);
  }

  @Override
  public void delete(String baseName) {
    sdblModels.remove(baseName);
    lineParsModels.remove(baseName);
    fullParsModels.remove(baseName);
    hierarchies.remove(baseName);
    verificationReports.remove(baseName);
  }

  @Override
  public List<String> listBaseNames() {
    return new ArrayList<>(sdblModels.keySet());
  }
}
