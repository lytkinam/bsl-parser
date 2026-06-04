package com.github._1c_syntax.bsl.parser.sdql.full_pars;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FullParsModel {
  private List<FullParsNode> nodes = new ArrayList<>();
}
