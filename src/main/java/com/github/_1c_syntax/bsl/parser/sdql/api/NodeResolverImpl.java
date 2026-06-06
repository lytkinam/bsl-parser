package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;

public class NodeResolverImpl implements NodeResolver {

  @Override
  public FullParsNode resolve(FullParsModel model, Integer nodeId, String nodeName) {
    if (nodeId != null) {
      for (FullParsNode node : model.getNodes()) {
        if (nodeId.equals(node.getId())) {
          return node;
        }
      }
      throw new NodeNotFoundException("Node not found: id=" + nodeId);
    }

    if (nodeName != null) {
      for (FullParsNode node : model.getNodes()) {
        if (nodeName.equals(node.getName())) {
          return node;
        }
      }
      throw new NodeNotFoundException("Node not found: name=" + nodeName);
    }

    throw new SdqlApiException("MISSING_NODE", "Either nodeId or nodeName must be provided");
  }

  @Override
  public FullParsSelectField resolveField(FullParsNode node, String alias) {
    if (node.getSelect() == null) {
      throw new AliasNotFoundException("Alias '" + alias + "' not found in node " + node.getId());
    }
    for (FullParsSelectField sf : node.getSelect()) {
      if (alias.equals(sf.getAlias())) {
        return sf;
      }
    }
    throw new AliasNotFoundException("Alias '" + alias + "' not found in node " + node.getId());
  }
}
