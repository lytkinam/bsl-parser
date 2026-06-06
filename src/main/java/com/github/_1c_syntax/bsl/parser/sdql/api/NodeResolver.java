package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;

/**
 * Resolution of node identifiers.
 */
public interface NodeResolver {

  /**
   * Find a node by ID or name.
   *
   * @param model    FULL_PARS model
   * @param nodeId   Node ID (priority)
   * @param nodeName Node name
   * @return FullParsNode
   * @throws NodeNotFoundException if node not found
   */
  FullParsNode resolve(FullParsModel model, Integer nodeId, String nodeName);

  /**
   * Find a field by alias in a node.
   *
   * @param node  Node
   * @param alias Field alias
   * @return FullParsSelectField
   * @throws AliasNotFoundException if field not found
   */
  FullParsSelectField resolveField(FullParsNode node, String alias);
}
