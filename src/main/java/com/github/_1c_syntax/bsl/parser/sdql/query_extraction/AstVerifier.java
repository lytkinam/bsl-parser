package com.github._1c_syntax.bsl.parser.sdql.query_extraction;

import com.github._1c_syntax.bsl.parser.SDBLParser;
import com.github._1c_syntax.bsl.parser.SDBLTokenizer;
import com.github._1c_syntax.bsl.parser.sdql.model.*;
import com.github._1c_syntax.bsl.parser.sdql.visitor.QueryPackageVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies extracted SQL by parsing both extracted and primary texts through SDBL parser
 * and comparing their AST structures.
 */
public class AstVerifier {

  /**
   * Parse a single SQL query and return its AST.
   * Wraps the query in a dummy package context for the visitor.
   */
  public QueryAst parseQuery(String sql) {
    try {
      SDBLTokenizer tokenizer = new SDBLTokenizer(sql);
      SDBLParser.QueryPackageContext ast = tokenizer.getAst();
      if (ast == null || ast.queries() == null || ast.queries().isEmpty()) {
        return null;
      }
      // Use a visitor with empty query names (single query parsing)
      QueryPackageVisitor visitor = new QueryPackageVisitor(sql, List.of());
      List<QueryAst> result = visitor.visitQueryPackage(ast);
      if (result == null || result.isEmpty()) {
        return null;
      }
      return result.get(0);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Compare two QueryAst structures and return list of differences.
   */
  public List<String> compareAst(QueryAst extracted, QueryAst primary) {
    List<String> diffs = new ArrayList<>();
    if (extracted == null && primary == null) {
      return diffs;
    }
    if (extracted == null) {
      diffs.add("Extracted AST is null");
      return diffs;
    }
    if (primary == null) {
      diffs.add("Primary AST is null");
      return diffs;
    }

    // Compare SELECT fields
    compareSelect(extracted.getSelect(), primary.getSelect(), diffs);

    // Compare FROM sources
    compareFrom(extracted.getFrom(), primary.getFrom(), diffs);

    // Compare WHERE
    compareWhere(extracted.getWhere(), primary.getWhere(), diffs);

    // Compare GROUP BY
    compareGroupBy(extracted.getGroupBy(), primary.getGroupBy(), diffs);

    // Compare HAVING
    compareHaving(extracted.getHaving(), primary.getHaving(), diffs);

    // Compare ORDER BY
    compareOrderBy(extracted.getOrderBy(), primary.getOrderBy(), diffs);

    // Compare INTO
    compareString("INTO", extracted.getInto(), primary.getInto(), diffs);

    // Compare limitations
    compareString("LIMITATIONS", extracted.getLimitations(), primary.getLimitations(), diffs);

    // Compare UNIONs
    compareUnions(extracted.getUnions(), primary.getUnions(), diffs);

    return diffs;
  }

  private void compareSelect(List<SelectField> extracted, List<SelectField> primary, List<String> diffs) {
    int extSize = extracted != null ? extracted.size() : 0;
    int primSize = primary != null ? primary.size() : 0;
    if (extSize != primSize) {
      diffs.add("SELECT field count differs: extracted=" + extSize + " primary=" + primSize);
      return;
    }
    if (extracted == null || primary == null) return;
    for (int i = 0; i < extracted.size(); i++) {
      SelectField ef = extracted.get(i);
      SelectField pf = primary.get(i);
      String eText = normalizeFieldText(ef.getText());
      String pText = normalizeFieldText(pf.getText());
      if (!eText.equals(pText)) {
        diffs.add("SELECT[" + i + "] text differs: extracted='" + eText + "' primary='" + pText + "'");
      }
      String eAlias = ef.getAlias() != null ? ef.getAlias() : "";
      String pAlias = pf.getAlias() != null ? pf.getAlias() : "";
      // If primary has no alias but extracted has auto-generated alias from text — not a real difference
      // This happens when original SQL omits КАК (e.g. "Таблица.Поле" without alias)
      if (!eAlias.equals(pAlias)) {
        // Use original text (not normalized) for alias extraction to preserve case
        String eTextOrig = ef.getText() != null ? ef.getText() : "";
        if (pAlias.isEmpty() && eAlias.equals(extractAliasFromText(eTextOrig))) {
          // Extracted alias is auto-generated from field text, primary has no alias — OK
        } else {
          diffs.add("SELECT[" + i + "] alias differs: extracted='" + eAlias + "' primary='" + pAlias + "'");
        }
      }
    }
  }

  private void compareFrom(List<DataSource> extracted, List<DataSource> primary, List<String> diffs) {
    int extSize = extracted != null ? extracted.size() : 0;
    int primSize = primary != null ? primary.size() : 0;
    if (extSize != primSize) {
      diffs.add("FROM source count differs: extracted=" + extSize + " primary=" + primSize);
      return;
    }
    if (extracted == null || primary == null) return;
    for (int i = 0; i < extracted.size(); i++) {
      DataSource ed = extracted.get(i);
      DataSource pd = primary.get(i);
      compareDataSource(ed, pd, i, diffs);
    }
  }

  private void compareDataSource(DataSource extracted, DataSource primary, int index, List<String> diffs) {
    String eSource = getSourceName(extracted);
    String pSource = getSourceName(primary);
    // Normalize: compare table names ignoring alias differences for subqueries
    if (!eSource.equals(pSource)) {
      diffs.add("FROM[" + index + "] source differs: extracted='" + eSource + "' primary='" + pSource + "'");
    }

    String eAlias = extracted.getAlias() != null ? extracted.getAlias() : "";
    String pAlias = primary.getAlias() != null ? primary.getAlias() : "";
    if (!eAlias.equals(pAlias)) {
      diffs.add("FROM[" + index + "] alias differs: extracted='" + eAlias + "' primary='" + pAlias + "'");
    }

    // Compare JOINs
    int eJoins = extracted.getJoins() != null ? extracted.getJoins().size() : 0;
    int pJoins = primary.getJoins() != null ? primary.getJoins().size() : 0;
    if (eJoins != pJoins) {
      diffs.add("FROM[" + index + "] join count differs: extracted=" + eJoins + " primary=" + pJoins);
      return;
    }
    if (extracted.getJoins() != null && primary.getJoins() != null) {
      for (int j = 0; j < extracted.getJoins().size(); j++) {
        JoinPart ej = extracted.getJoins().get(j);
        JoinPart pj = primary.getJoins().get(j);
        compareJoin(ej, pj, index, j, diffs);
      }
    }
  }

  private String getSourceName(DataSource ds) {
    String text;
    if (ds.getTable() != null) text = ds.getTable();
    else if (ds.getVirtualTable() != null) text = ds.getVirtualTable().getText();
    else if (ds.getSubquery() != null) return "<subquery>";
    else if (ds.getExternalDataSource() != null) text = ds.getExternalDataSource();
    else if (ds.getParameterTable() != null) text = ds.getParameterTable();
    else return "";
    // Normalize line endings for comparison
    return text.replace("\r\n", "\n").replace("\r", "\n");
  }

  private void compareJoin(JoinPart extracted, JoinPart primary, int dsIndex, int joinIndex, List<String> diffs) {
    String eType = extracted.getJoinType() != null ? extracted.getJoinType() : "left";
    String pType = primary.getJoinType() != null ? primary.getJoinType() : "left";
    if (!eType.equals(pType)) {
      diffs.add("FROM[" + dsIndex + "].JOIN[" + joinIndex + "] type differs: extracted='" + eType + "' primary='" + pType + "'");
    }

    String eCond = normalizeCondition(extracted.getCondition());
    String pCond = normalizeCondition(primary.getCondition());
    if (!eCond.equals(pCond)) {
      diffs.add("FROM[" + dsIndex + "].JOIN[" + joinIndex + "] condition differs: extracted='" + eCond + "' primary='" + pCond + "'");
    }
  }

  private void compareWhere(WhereBlock extracted, WhereBlock primary, List<String> diffs) {
    String eText = extracted != null && extracted.getText() != null ? normalizeCondition(extracted.getText()) : "";
    String pText = primary != null && primary.getText() != null ? normalizeCondition(primary.getText()) : "";
    if (!eText.equals(pText)) {
      diffs.add("WHERE differs: extracted='" + eText + "' primary='" + pText + "'");
    }
  }

  private void compareGroupBy(List<String> extracted, List<String> primary, List<String> diffs) {
    int extSize = extracted != null ? extracted.size() : 0;
    int primSize = primary != null ? primary.size() : 0;
    if (extSize != primSize) {
      diffs.add("GROUP BY count differs: extracted=" + extSize + " primary=" + primSize);
      return;
    }
    if (extracted == null || primary == null) return;
    for (int i = 0; i < extracted.size(); i++) {
      String eText = normalizeCondition(extracted.get(i));
      String pText = normalizeCondition(primary.get(i));
      if (!eText.equals(pText)) {
        diffs.add("GROUP BY[" + i + "] differs: extracted='" + eText + "' primary='" + pText + "'");
      }
    }
  }

  private void compareHaving(HavingBlock extracted, HavingBlock primary, List<String> diffs) {
    String eText = extracted != null && extracted.getText() != null ? normalizeCondition(extracted.getText()) : "";
    String pText = primary != null && primary.getText() != null ? normalizeCondition(primary.getText()) : "";
    if (!eText.equals(pText)) {
      diffs.add("HAVING differs: extracted='" + eText + "' primary='" + pText + "'");
    }
  }

  private void compareOrderBy(List<String> extracted, List<String> primary, List<String> diffs) {
    int extSize = extracted != null ? extracted.size() : 0;
    int primSize = primary != null ? primary.size() : 0;
    if (extSize != primSize) {
      diffs.add("ORDER BY count differs: extracted=" + extSize + " primary=" + primSize);
      return;
    }
    if (extracted == null || primary == null) return;
    for (int i = 0; i < extracted.size(); i++) {
      String eText = normalizeCondition(extracted.get(i));
      String pText = normalizeCondition(primary.get(i));
      if (!eText.equals(pText)) {
        diffs.add("ORDER BY[" + i + "] differs: extracted='" + eText + "' primary='" + pText + "'");
      }
    }
  }

  private void compareUnions(List<UnionPart> extracted, List<UnionPart> primary, List<String> diffs) {
    int extSize = extracted != null ? extracted.size() : 0;
    int primSize = primary != null ? primary.size() : 0;
    if (extSize != primSize) {
      diffs.add("UNION count differs: extracted=" + extSize + " primary=" + primSize);
    }
  }

  private void compareString(String field, String extracted, String primary, List<String> diffs) {
    String e = extracted != null ? extracted : "";
    String p = primary != null ? primary : "";
    if (!e.equals(p)) {
      diffs.add(field + " differs: extracted='" + e + "' primary='" + p + "'");
    }
  }

  /**
   * Normalize field text for comparison: collapse whitespace, uppercase.
   */
  private String normalizeFieldText(String text) {
    if (text == null) return "";
    return text.replaceAll("\\s+", " ").trim().toUpperCase();
  }

  /**
   * Extract auto-generated alias from field text (e.g. "Таблица.Поле" → "ТаблицаПоле").
   * This matches the behavior of FullParsModelBuilder when КАК is omitted.
   */
  private String extractAliasFromText(String text) {
    if (text == null) return "";
    // Remove parentheses and function calls, keep only the last identifier part
    String result = text;
    // Remove SUM( ... ) etc.
    if (result.contains("(")) {
      int lastParen = result.lastIndexOf(')');
      if (lastParen > 0) {
        result = result.substring(0, lastParen + 1);
      }
    }
    // For dotted names like "Таблица.Поле", alias is typically "ТаблицаПоле"
    // FullParsModelBuilder also removes underscores from VT names (ВТ_Имя → ВТИмя)
    result = result.replace(".", "");
    result = result.replace("(", "");
    result = result.replace(")", "");
    result = result.replace(" ", "");
    result = result.replace("_", "");
    return result;
  }

  /**
   * Normalize condition text for comparison: collapse whitespace, uppercase.
   */
  private String normalizeCondition(String text) {
    if (text == null) return "";
    return text.replaceAll("\\s+", " ").trim().toUpperCase();
  }
}
