package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;

import java.util.List;
import java.util.Map;

/**
 * SQL restoration from FFL.
 */
public interface QueryRestorer {

  /**
   * Restore SQL from a list of FFL nodes.
   *
   * @param fflNodes     List of FFL nodes
   * @param fullParsById Full model (for inline subqueries)
   * @return SQL text
   */
  String restore(List<FullParsNode> fflNodes, Map<Integer, FullParsNode> fullParsById);

  /**
   * Restore SQL from a list of FFL nodes (FFL only, without full model).
   *
   * @param fflNodes List of FFL nodes
   * @return SQL text
   */
  String restore(List<FullParsNode> fflNodes);
}
