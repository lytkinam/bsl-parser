package com.github._1c_syntax.bsl.parser.sdql.md;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.fields.FieldRecord;
import com.github._1c_syntax.bsl.parser.sdql.fields.FieldRef;
import com.github._1c_syntax.bsl.parser.sdql.fields.TableAlias;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class FieldsNodeMdBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper()
    .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

  public void build(Path outputDir, String baseName) throws IOException {
    Path fnDir = outputDir.resolve("fields_node_" + baseName);
    if (!Files.exists(fnDir)) return;

    buildFieldsNode(fnDir, baseName);
    buildTableAliasMap(fnDir, baseName);
  }

  private void buildFieldsNode(Path fnDir, String baseName) throws IOException {
    Path jsonFile = fnDir.resolve("fields_node.json");
    if (!Files.exists(jsonFile)) return;

    Map<String, List<FieldRecord>> data = MAPPER.readValue(jsonFile.toFile(),
      new TypeReference<Map<String, List<FieldRecord>>>() {});

    StringBuilder sb = new StringBuilder();
    sb.append("# Анализ полей: ").append(baseName).append("\n\n");

    for (Map.Entry<String, List<FieldRecord>> entry : data.entrySet()) {
      String nodeId = entry.getKey();
      List<FieldRecord> records = entry.getValue();

      sb.append("## Запрос ").append(nodeId).append("\n\n");
      if (records == null || records.isEmpty()) {
        sb.append("Поля отсутствуют.\n\n");
        continue;
      }

      sb.append("| Псевдоним | Выражение | Тип | Ссылки на поля |\n");
      sb.append("|---|---|---|---|\n");
      for (FieldRecord rec : records) {
        String refs = "";
        if (rec.getFieldRefs() != null && !rec.getFieldRefs().isEmpty()) {
          StringBuilder refSb = new StringBuilder();
          for (FieldRef fr : rec.getFieldRefs()) {
            if (refSb.length() > 0) refSb.append(", ");
            refSb.append(nullSafe(fr.getPrimaryTable())).append(".").append(nullSafe(fr.getField()));
          }
          refs = refSb.toString();
        }
        sb.append("| ").append(escapeMd(nullSafe(rec.getAlias())))
          .append(" | ").append(escapeMd(nullSafe(rec.getExpressionRaw())))
          .append(" | ").append(escapeMd(nullSafe(rec.getExprType())))
          .append(" | ").append(escapeMd(refs))
          .append(" |\n");
      }
      sb.append("\n");
    }

    Files.writeString(fnDir.resolve("fields_node.md"), sb.toString());
  }

  private void buildTableAliasMap(Path fnDir, String baseName) throws IOException {
    Path jsonFile = fnDir.resolve("table_alias_map.json");
    if (!Files.exists(jsonFile)) return;

    Map<String, List<TableAlias>> data = MAPPER.readValue(jsonFile.toFile(),
      new TypeReference<Map<String, List<TableAlias>>>() {});

    StringBuilder sb = new StringBuilder();
    sb.append("# Алиасы таблиц: ").append(baseName).append("\n\n");

    for (Map.Entry<String, List<TableAlias>> entry : data.entrySet()) {
      String nodeId = entry.getKey();
      List<TableAlias> aliases = entry.getValue();

      sb.append("## Запрос ").append(nodeId).append("\n\n");
      if (aliases == null || aliases.isEmpty()) {
        sb.append("Алиасы отсутствуют.\n\n");
        continue;
      }

      sb.append("| Алиас | Основная таблица | Тип соединения | Виртуальная |\n");
      sb.append("|---|---|---|---|\n");
      for (TableAlias ta : aliases) {
        sb.append("| ").append(escapeMd(nullSafe(ta.getAlias())))
          .append(" | ").append(escapeMd(nullSafe(ta.getPrimaryTable())))
          .append(" | ").append(escapeMd(nullSafe(ta.getJoinType())))
          .append(" | ").append(ta.isVirtual() ? "Да" : "Нет")
          .append(" |\n");
      }
      sb.append("\n");
    }

    Files.writeString(fnDir.resolve("table_alias_map.md"), sb.toString());
  }

  private String nullSafe(String s) {
    return s != null ? s : "";
  }

  private String escapeMd(String s) {
    if (s == null) return "";
    return s.replace("|", "\\|").replace("\n", "<br>").replace("\r", "");
  }
}
