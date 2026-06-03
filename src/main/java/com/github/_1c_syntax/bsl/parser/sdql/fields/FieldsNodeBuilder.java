package com.github._1c_syntax.bsl.parser.sdql.fields;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FieldsNodeBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
  private static final Pattern FIELD_REF_PATTERN = Pattern.compile("(?U)([\\w]+)\\.([\\w.]+)");
  private static final Set<String> KEYWORDS = Set.of(
    "NULL", "ИСТИНА", "ЛОЖЬ", "TRUE", "FALSE", "ЗНАЧЕНИЕ", "ДОБАВИТЬКДАТЕ",
    "НАЧАЛОПЕРИОДА", "КОНЕЦПЕРИОДА", "СУММА", "МИНИМУМ", "МАКСИМУМ", "СРЕДНЕЕ", "КОЛИЧЕСТВО"
  );

  public void build(QueryModel model, Path outputDir) throws IOException {
    Map<String, List<FieldRecord>> fieldsNode = new HashMap<>();
    Map<String, List<TableAlias>> tableAliasMap = new HashMap<>();

    for (QueryNode node : model.getNodes()) {
      String nid = String.valueOf(node.getId());
      QueryAst ast = node.getQuery();
      if (ast == null || !"select".equals(ast.getType())) {
        fieldsNode.put(nid, List.of());
        tableAliasMap.put(nid, List.of());
        continue;
      }

      List<TableAlias> aliases = parseAliasMap(ast);
      tableAliasMap.put(nid, aliases);

      List<FieldRecord> records = new ArrayList<>();

      // SELECT fields
      if (ast.getSelect() != null) {
        for (SelectField sf : ast.getSelect()) {
          FieldRecord rec = new FieldRecord();
          rec.setAlias(sf.getAlias() != null ? sf.getAlias() : sanitizeAlias(sf.getText()));
          rec.setExpressionRaw(sf.getText());
          rec.setExprType(classifyExpr(sf.getText()));
          rec.setFieldRefs(extractFieldRefs(sf.getText(), aliases));
          records.add(rec);
        }
      }

      // WHERE pseudo-field
      if (ast.getWhere() != null) {
        FieldRecord rec = new FieldRecord();
        rec.setAlias("ГДЕ_УСЛОВИЕ");
        rec.setExpressionRaw(ast.getWhere());
        rec.setExprType("where_condition");
        rec.setFieldRefs(extractFieldRefs(ast.getWhere(), aliases));
        records.add(rec);
      }

      // JOIN pseudo-fields
      if (ast.getFrom() != null) {
        for (DataSource ds : ast.getFrom()) {
          if (ds.getJoins() != null) {
            for (JoinPart jp : ds.getJoins()) {
              DataSource src = jp.getSource();
              String joinType = "ЛЕВОЕ_СОЕДИНЕНИЕ";
              if ("right".equals(jp.getJoinType())) joinType = "ПРАВОЕ_СОЕДИНЕНИЕ";
              else if ("full".equals(jp.getJoinType())) joinType = "ПОЛНОЕ_СОЕДИНЕНИЕ";
              else if ("inner".equals(jp.getJoinType())) joinType = "ВНУТРЕННЕЕ_СОЕДИНЕНИЕ";

              String alias = src.getAlias() != null ? src.getAlias() : "?";

              // JOIN table
              FieldRecord tableRec = new FieldRecord();
              tableRec.setAlias(joinType + "_" + alias);
              tableRec.setExpressionRaw(src.getTable() != null ? src.getTable() :
                src.getVirtualTable() != null ? src.getVirtualTable() :
                  src.getParameterTable() != null ? src.getParameterTable() :
                    src.getExternalDataSource() != null ? src.getExternalDataSource() : "?");
              tableRec.setExprType("join_table");
              tableRec.setFieldRefs(List.of());
              records.add(tableRec);

              // JOIN ON condition
              if (jp.getCondition() != null) {
                FieldRecord condRec = new FieldRecord();
                condRec.setAlias(joinType + "_" + alias + "_УСЛОВИЕ");
                condRec.setExpressionRaw(jp.getCondition());
                condRec.setExprType("join_on_condition");
                condRec.setFieldRefs(extractFieldRefs(jp.getCondition(), aliases));
                records.add(condRec);
              }
            }
          }
        }
      }

      fieldsNode.put(nid, records);
    }

    Path fnDir = outputDir.resolve("fields_node");
    Files.createDirectories(fnDir);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(fnDir.resolve("fields_node.json").toFile(), fieldsNode);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(fnDir.resolve("table_alias_map.json").toFile(), tableAliasMap);
  }

  private List<TableAlias> parseAliasMap(QueryAst ast) {
    List<TableAlias> result = new ArrayList<>();
    if (ast.getFrom() == null) return result;
    for (DataSource ds : ast.getFrom()) {
      addAlias(ds, "ИЗ", result);
      if (ds.getJoins() != null) {
        for (JoinPart jp : ds.getJoins()) {
          String jt = "СОЕДИНЕНИЕ";
          if ("left".equals(jp.getJoinType())) jt = "ЛЕВОЕ_СОЕДИНЕНИЕ";
          else if ("right".equals(jp.getJoinType())) jt = "ПРАВОЕ_СОЕДИНЕНИЕ";
          else if ("full".equals(jp.getJoinType())) jt = "ПОЛНОЕ_СОЕДИНЕНИЕ";
          else if ("inner".equals(jp.getJoinType())) jt = "ВНУТРЕННЕЕ_СОЕДИНЕНИЕ";
          addAlias(jp.getSource(), jt, result);
        }
      }
    }
    return result;
  }

  private void addAlias(DataSource ds, String joinType, List<TableAlias> result) {
    String primary = ds.getTable() != null ? ds.getTable() :
      ds.getVirtualTable() != null ? ds.getVirtualTable() :
        ds.getParameterTable() != null ? ds.getParameterTable() :
          ds.getExternalDataSource() != null ? ds.getExternalDataSource() : "?";
    String alias = ds.getAlias() != null ? ds.getAlias() : primary;
    TableAlias ta = new TableAlias();
    ta.setAlias(alias);
    ta.setPrimaryTable(primary);
    ta.setVirtual(primary.toUpperCase().startsWith("ВТ_"));
    ta.setJoinType(joinType);
    result.add(ta);
  }

  private String classifyExpr(String expr) {
    if (expr == null || expr.trim().isEmpty()) return "literal";
    String e = expr.trim().toUpperCase();
    if (e.equals("*")) return "star";
    if (e.startsWith("ВЫБОР ") || e.startsWith("CASE ")) return "case_when";
    if (e.startsWith("\"") || e.startsWith("'") || e.startsWith("&") || e.matches("^\\d.*")) return "literal";
    if (e.equals("NULL") || e.equals("ИСТИНА") || e.equals("ЛОЖЬ")) return "literal";
    if (e.matches("^[А-ЯA-Z_]+\\s*\\(")) {
      String name = e.substring(0, e.indexOf('(')).trim();
      if (KEYWORDS.contains(name)) {
        if (name.equals("СУММА") || name.equals("МИНИМУМ") || name.equals("МАКСИМУМ") || name.equals("СРЕДНЕЕ") || name.equals("КОЛИЧЕСТВО")) {
          return "aggregate";
        }
        return "func_call";
      }
    }
    if (e.contains("+") || e.contains("-") || e.contains("*") || e.contains("/")) return "arithmetic";
    return "field_ref";
  }

  private List<FieldRef> extractFieldRefs(String expr, List<TableAlias> aliases) {
    Map<String, String> lookup = new HashMap<>();
    for (TableAlias ta : aliases) {
      lookup.put(ta.getAlias().toUpperCase(), ta.getPrimaryTable());
    }

    List<FieldRef> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    Matcher m = FIELD_REF_PATTERN.matcher(expr);
    while (m.find()) {
      String aliasTable = m.group(1);
      String field = m.group(2);
      if (KEYWORDS.contains(aliasTable.toUpperCase())) continue;
      String key = aliasTable.toUpperCase() + "." + field.toUpperCase();
      if (seen.contains(key)) continue;
      seen.add(key);

      FieldRef ref = new FieldRef();
      ref.setAliasTable(aliasTable);
      ref.setField(field);
      ref.setPrimaryTable(lookup.getOrDefault(aliasTable.toUpperCase(), aliasTable));
      result.add(ref);
    }
    return result;
  }



  private String sanitizeAlias(String text) {
    if (text == null) return "";
    return text.replaceAll("[^\\p{L}\\p{N}]", "");
  }

}
