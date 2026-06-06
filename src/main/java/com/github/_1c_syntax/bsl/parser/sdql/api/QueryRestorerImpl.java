package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.query_reconstruction.QueryReconstructor;

import java.util.List;
import java.util.Map;

public class QueryRestorerImpl implements QueryRestorer {

  private final QueryReconstructor reconstructor = new QueryReconstructor();

  @Override
  public String restore(List<FullParsNode> fflNodes, Map<Integer, FullParsNode> fullParsById) {
    return reconstructor.reconstruct(fflNodes, fullParsById);
  }

  @Override
  public String restore(List<FullParsNode> fflNodes) {
    return reconstructor.reconstruct(fflNodes);
  }
}
