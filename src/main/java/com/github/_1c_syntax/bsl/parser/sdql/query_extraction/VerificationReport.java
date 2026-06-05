package com.github._1c_syntax.bsl.parser.sdql.query_extraction;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class VerificationReport {
  private String baseName;
  private int totalNodes;
  private int matched;
  private int mismatched;
  private List<NodeVerificationResult> nodes = new ArrayList<>();
}
