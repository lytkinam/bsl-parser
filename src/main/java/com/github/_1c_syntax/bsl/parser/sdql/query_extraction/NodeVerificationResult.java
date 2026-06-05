package com.github._1c_syntax.bsl.parser.sdql.query_extraction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class NodeVerificationResult {
  private int id;
  private String name;
  private String status; // "matched" | "mismatched" | "skipped"
  private String primaryHash;
  private String extractedHash;
  private List<String> differences = new ArrayList<>();
}
