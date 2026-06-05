package com.github._1c_syntax.bsl.parser.sdql.query_extraction;

public class TextNormalizer {

  public static String normalize(String text) {
    if (text == null) {
      return "";
    }
    text = removeComments(text);
    text = text.replace("\n", " ").replace("\r", " ");
    text = text.replaceAll("\\s+", " ");
    text = text.toUpperCase().trim();
    return text;
  }

  private static String removeComments(String text) {
    // Remove // comments
    text = text.replaceAll("//[^\\n]*", "");
    // Remove /* */ comments (non-greedy)
    text = text.replaceAll("/\\*.*?\\*/", "");
    return text;
  }
}
