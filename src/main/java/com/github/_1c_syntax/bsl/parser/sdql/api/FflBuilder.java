package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullFieldLineageBuilder;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wrapper around FullFieldLineageBuilder that properly initializes
 * the internal state for in-memory usage.
 */
public class FflBuilder {

  private final FullFieldLineageBuilder builder = new FullFieldLineageBuilder();

  public List<FullParsNode> build(FullParsModel model, int nodeId, List<String> aliases) {
    // Initialize the builder's internal nodeById map
    initModel(model);
    return builder.buildLineage_debug(nodeId, aliases, false);
  }

  private void initModel(FullParsModel model) {
    try {
      java.lang.reflect.Field nodeByIdField = FullFieldLineageBuilder.class.getDeclaredField("nodeById");
      nodeByIdField.setAccessible(true);
      Map<Integer, FullParsNode> nodeById = model.getNodes().stream()
        .collect(Collectors.toMap(FullParsNode::getId, n -> n));
      nodeByIdField.set(builder, nodeById);

      // Also init nextFieldId
      java.lang.reflect.Field nextFieldIdField = FullFieldLineageBuilder.class.getDeclaredField("nextFieldId");
      nextFieldIdField.setAccessible(true);
      int maxFieldId = 0;
      for (FullParsNode node : model.getNodes()) {
        if (node.getSelect() != null) {
          for (var sf : node.getSelect()) {
            if (sf.getFieldId() != null && sf.getFieldId() > maxFieldId) {
              maxFieldId = sf.getFieldId();
            }
          }
        }
      }
      nextFieldIdField.setInt(builder, maxFieldId + 1);
    } catch (Exception e) {
      throw new SdqlApiException("PIPELINE_ERROR", "Failed to initialize FFL builder", e);
    }
  }
}
