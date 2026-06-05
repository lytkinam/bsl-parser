package com.github._1c_syntax.bsl.parser.sdql.query_extraction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class QueryVerifier {

  public NodeVerificationResult verify(int nodeId, String nodeName,
                                        String extractedSql, String primaryText) {
    String extractedNorm = TextNormalizer.normalize(extractedSql);
    String primaryNorm = TextNormalizer.normalize(primaryText);

    String extractedHash = sha256(extractedNorm);
    String primaryHash = sha256(primaryNorm);

    NodeVerificationResult result = new NodeVerificationResult();
    result.setId(nodeId);
    result.setName(nodeName);
    result.setPrimaryHash(primaryHash);
    result.setExtractedHash(extractedHash);

    if (extractedNorm.equals(primaryNorm)) {
      result.setStatus("matched");
    } else {
      result.setStatus("mismatched");
      result.setDifferences(computeDiff(extractedNorm, primaryNorm, 3));
    }

    return result;
  }

  private List<String> computeDiff(String extracted, String primary, int maxDiffs) {
    List<String> diffs = new ArrayList<>();
    int len = Math.min(extracted.length(), primary.length());
    int diffCount = 0;

    for (int i = 0; i < len && diffCount < maxDiffs; i++) {
      if (extracted.charAt(i) != primary.charAt(i)) {
        int start = Math.max(0, i - 20);
        int end = Math.min(len, i + 20);
        String extCtx = extracted.substring(start, end);
        String primCtx = primary.substring(start, end);
        diffs.add("Extracted: ..." + extCtx + "... vs Primary: ..." + primCtx + "...");
        diffCount++;
      }
    }

    if (extracted.length() != primary.length() && diffCount < maxDiffs) {
      diffs.add("Length differs: extracted=" + extracted.length() + " primary=" + primary.length());
    }

    return diffs;
  }

  private String sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
  }
}
