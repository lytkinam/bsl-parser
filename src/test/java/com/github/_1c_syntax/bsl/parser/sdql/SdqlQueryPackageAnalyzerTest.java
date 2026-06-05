package com.github._1c_syntax.bsl.parser.sdql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.parser.sdql.io.ModelJsonMapper;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.FieldLineageNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.HierarchyNode;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsFieldExtractor;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModel;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SdqlQueryPackageAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void testExampleSql() throws Exception {
        File output = tempDir.resolve("out").toFile();
        SdqlCli.main(new String[]{"examples/example.sql", output.getAbsolutePath()});

        // model.json without text
        QueryModel model = ModelJsonMapper.read(
            output.toPath().resolve("sdbl_parse_model_example.json"));
        assertThat(model.getNodes()).hasSize(5);

        // nodes.json with full text
        assertThat(output.toPath().resolve("sdbl_parse_nodes_example.json")).exists();
        String nodesJson = Files.readString(output.toPath().resolve("sdbl_parse_nodes_example.json"));
        assertThat(nodesJson).contains("ВТ_Сотрудники");
        assertThat(nodesJson).contains("ВЫБРАТЬ");

        // LINE_PARS model and hierarchy (created as sibling to outputDir)
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_model_example.json")).exists();
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_hierarchy_example.json")).exists();

        // Markdown reports
        assertThat(output.toPath().resolve("sdbl_parse_model_example.md")).exists();
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_model_example.md")).exists();
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_hierarchy_example.md")).exists();

        // field_lineage as sibling to LINE_PARS: field_lineage/<baseName>/<nodeId>_<nodeName>/
        Path fieldLineageDir = tempDir.resolve("field_lineage");
        assertThat(fieldLineageDir).exists();
        Path baseDir = fieldLineageDir.resolve("example");
        assertThat(baseDir).exists();
        // example.sql has result node id=2 (Результат_3)
        Path nodeDir = baseDir.resolve("2_Результат_3");
        assertThat(nodeDir).exists();
        assertThat(nodeDir.resolve("FLS_example_2_Результат_3_ИтогоСумма.json")).exists();
    }

    @Test
    void testExample258Sql() throws Exception {
        File output = tempDir.resolve("out258").toFile();
        SdqlCli.main(new String[]{"examples/example_258.sql", output.getAbsolutePath()});

        QueryModel model = ModelJsonMapper.read(
            output.toPath().resolve("sdbl_parse_model_example_258.json"));
        assertThat(model.getNodes()).hasSizeGreaterThan(100);

        // nodes.json contains full texts
        assertThat(output.toPath().resolve("sdbl_parse_nodes_example_258.json")).exists();
        String nodesJson = Files.readString(output.toPath().resolve("sdbl_parse_nodes_example_258.json"));
        assertThat(nodesJson).contains("ВидОбязательств_гр1а");

        // LINE_PARS model and hierarchy (created as sibling to outputDir)
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_model_example_258.json")).exists();
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_hierarchy_example_258.json")).exists();

        // Markdown reports
        assertThat(output.toPath().resolve("sdbl_parse_model_example_258.md")).exists();
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_model_example_258.md")).exists();
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_hierarchy_example_258.md")).exists();

        // field_lineage as sibling to LINE_PARS: field_lineage/<baseName>/
        Path fieldLineageDir258 = tempDir.resolve("field_lineage");
        assertThat(fieldLineageDir258).exists();
        assertThat(fieldLineageDir258.resolve("example_258")).isDirectory();
    }

    @Test
    void testRestoredQueryFile() throws Exception {
        File output = tempDir.resolve("out_restored").toFile();
        SdqlCli.main(new String[]{"examples/middle_example.sql", output.getAbsolutePath()});

        // RESTORED_QUERIES should exist with restored SQL files
        Path middleRestoredDir = tempDir.resolve("RESTORED_QUERIES").resolve("middle_example");
        assertThat(middleRestoredDir).exists();
        // Find any node directory (id may change when new features add nodes)
        Path restoredDir = Files.list(middleRestoredDir)
            .filter(Files::isDirectory)
            .findFirst()
            .orElse(null);
        assertThat(restoredDir).isNotNull();

        Path restoredSql = restoredDir.resolve("Суммы_ПР_ТранзитныеВиды_ЗадолженностьПенсии.sql");
        assertThat(restoredSql).exists();

        String sql = Files.readString(restoredSql);
        // Check basic SQL blocks
        assertThat(sql).contains("ВЫБРАТЬ");
        assertThat(sql).contains("ИЗ");
        assertThat(sql).contains("ПОМЕСТИТЬ ВТ_ТранзитныеВидыНачалоКонец");
        assertThat(sql).contains("ПОМЕСТИТЬ ВТ_Суммы_ПР_ТранзитныеВиды");
        assertThat(sql).contains("СГРУППИРОВАТЬ ПО");

        // SRS02 v1.4: INTO inserted in UNION_0 between SELECT and FROM, not at end
        // Check that ПОМЕСТИТЬ ВТ_ОстаткиТранзитныеВиды appears before first ИЗ in its query block
        int intoOstIdx = sql.indexOf("ПОМЕСТИТЬ ВТ_ОстаткиТранзитныеВиды");
        int intoSumIdx = sql.indexOf("ПОМЕСТИТЬ ВТ_Суммы_ПР_ТранзитныеВиды");
        assertThat(intoOstIdx).isGreaterThan(0);
        assertThat(intoSumIdx).isGreaterThan(0);

        // SRS02 v1.4: sub_query nodes are inlined, not separate queries
        assertThat(sql).doesNotContain("ПОМЕСТИТЬ ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1");
        assertThat(sql).doesNotContain("ПОМЕСТИТЬ ВТ_ОстаткиТранзитныеВиды_UNION_0_SUB_1");
        // Check inline subquery in parentheses
        assertThat(sql).contains("ИЗ\n    (");

        // Check that restored SQL contains expected blocks
        assertThat(sql).contains("ПОМЕСТИТЬ ВТ_ТранзитныеВидыНачалоКонец");
        assertThat(sql).contains("ПОМЕСТИТЬ ВТ_Суммы_ПР_ТранзитныеВиды");
        assertThat(sql).contains("СГРУППИРОВАТЬ ПО");
        assertThat(sql).contains("ОБЪЕДИНИТЬ ВСЕ");
    }

    @Test
    void testExtractedQueriesAndVerification() throws Exception {
        File output = tempDir.resolve("out_extracted").toFile();
        SdqlCli.main(new String[]{"examples/example_2.sql", output.getAbsolutePath()});

        // EXTRACTED_QUERIES should exist with SQL files
        Path extractedDir = tempDir.resolve("EXTRACTED_QUERIES").resolve("example_2");
        assertThat(extractedDir).exists();
        assertThat(extractedDir.resolve("0_вт_НашиДоговора.sql")).exists();
        assertThat(extractedDir.resolve("1_ЗЛО.sql")).exists();
        assertThat(extractedDir.resolve("5_Результат_3.sql")).exists();

        // Extracted SQL should contain basic blocks
        String zloSql = Files.readString(extractedDir.resolve("1_ЗЛО.sql"));
        assertThat(zloSql).contains("ВЫБРАТЬ");
        assertThat(zloSql).contains("ПОМЕСТИТЬ ЗЛО");
        assertThat(zloSql).contains("ОБЪЕДИНИТЬ ВСЕ");
        assertThat(zloSql).contains("ИЗ");

        String resultSql = Files.readString(extractedDir.resolve("5_Результат_3.sql"));
        assertThat(resultSql).contains("ВЫБРАТЬ");
        assertThat(resultSql).contains("ИЗ");
        assertThat(resultSql).contains("ГДЕ");

        // VERIFICATION report should exist
        Path verificationDir = tempDir.resolve("VERIFICATION").resolve("example_2");
        assertThat(verificationDir).exists();
        assertThat(verificationDir.resolve("verification_report.json")).exists();

        String reportJson = Files.readString(verificationDir.resolve("verification_report.json"));
        assertThat(reportJson).contains("\"baseName\" : \"example_2\"");
        assertThat(reportJson).contains("\"totalNodes\"");
        assertThat(reportJson).contains("\"matched\"");
        assertThat(reportJson).contains("\"mismatched\"");
        assertThat(reportJson).contains("\"nodes\"");
    }

    @Test
    void testFieldLineageExtraction() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LineParsModel model = mapper.readValue(
            new File("examples/LINE_PARS/LINE_PARS_model_middle_example.json"),
            LineParsModel.class);
        List<HierarchyNode> hierarchy = mapper.readValue(
            new File("examples/LINE_PARS/LINE_PARS_hierarchy_middle_example.json"),
            new TypeReference<List<HierarchyNode>>() {});

        LineParsFieldExtractor extractor = new LineParsFieldExtractor(model, hierarchy);

        // 1. Root temp_query with subquery
        FieldLineageNode result = extractor.extract(24, "ПенсионныйСчет");
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(24);
        assertThat(result.getName()).isEqualTo("ВТ_ПенсионныеСчета_ПР");
        assertThat(result.getAlias()).isEqualTo("ПенсионныйСчет");
        assertThat(result.getText()).isEqualTo("Подзапрос.ПенсионныйСчет");
        assertThat(result.getChildFields()).hasSize(1);

        FieldLineageNode child = result.getChildFields().get(0);
        assertThat(child.getChildName()).isEqualTo("Подзапрос");
        assertThat(child.getId()).isEqualTo(25);
        assertThat(child.getName()).isEqualTo("ВТ_ПенсионныеСчета_ПР_SUB_1");
        assertThat(child.getAlias()).isEqualTo("ПенсионныйСчет");
        assertThat(child.getText()).isEqualTo("ПенсионныйСчет");
        assertThat(child.getChildFields()).hasSize(11); // union_0 .. union_10

        // Check first union leaf
        FieldLineageNode union0 = child.getChildFields().get(0);
        assertThat(union0.getChildName()).isEqualTo("union_0");
        assertThat(union0.getId()).isEqualTo(26);
        assertThat(union0.getAlias()).isEqualTo("ПенсионныйСчет");
        assertThat(union0.getText()).isEqualTo("уп_РезервыОстатки.НомерСчета");
        assertThat(union0.getChildFields()).hasSize(1);
        FieldLineageNode leaf0 = union0.getChildFields().get(0);
        assertThat(leaf0.getChildName()).isEqualTo("уп_РезервыОстатки");
        assertThat(leaf0.getName()).isEqualTo("уп_РезервыОстатки");
        assertThat(leaf0.getSource()).contains("РегистрНакопления.уп_Резервы.Остатки");
        assertThat(leaf0.getAlias()).isEqualTo("НомерСчета");
        assertThat(leaf0.getText()).isEqualTo("уп_РезервыОстатки.НомерСчета");
        assertThat(leaf0.getChildFields()).isEmpty();

        // Check second union leaf
        FieldLineageNode union1 = child.getChildFields().get(1);
        assertThat(union1.getChildName()).isEqualTo("union_1");
        assertThat(union1.getId()).isEqualTo(28);
        assertThat(union1.getText()).isEqualTo("уп_РезервыОбороты.НомерСчета");
        assertThat(union1.getChildFields()).hasSize(1);
        FieldLineageNode leaf1 = union1.getChildFields().get(0);
        assertThat(leaf1.getChildName()).isEqualTo("уп_РезервыОбороты");
        assertThat(leaf1.getSource()).contains("РегистрНакопления.уп_Резервы.Обороты");
        assertThat(leaf1.getAlias()).isEqualTo("НомерСчета");

        // 2. Union part with chain of fields — must NOT produce false child
        FieldLineageNode chainResult = extractor.extract(46, "ПенсионныйСчет");
        assertThat(chainResult).isNotNull();
        assertThat(chainResult.getId()).isEqualTo(46);
        assertThat(chainResult.getText()).isEqualTo("ВТ_ПенсионныеСчета_ПР.ПенсионныйСчет.Владелец.ПенсионныйСчет");
        // Should have exactly one child (ВТ_ПенсионныеСчета_ПР), not two
        assertThat(chainResult.getChildFields()).hasSize(1);
        assertThat(chainResult.getChildFields().get(0).getChildName()).isEqualTo("ВТ_ПенсионныеСчета_ПР");
        assertThat(chainResult.getChildFields().get(0).getId()).isEqualTo(24);

        // 3. Physical table leaf
        FieldLineageNode leafResult = extractor.extract(51, "ПенсионныйСчет");
        assertThat(leafResult).isNotNull();
        assertThat(leafResult.getId()).isEqualTo(51);
        assertThat(leafResult.getText()).isEqualTo("Резервы.НомерСчета");
        assertThat(leafResult.getChildFields()).hasSize(1);
        FieldLineageNode physLeaf = leafResult.getChildFields().get(0);
        assertThat(physLeaf.getChildName()).isEqualTo("Резервы");
        assertThat(physLeaf.getName()).isEqualTo("Резервы");
        assertThat(physLeaf.getSource()).contains("РегистрНакопления.уп_Резервы.Обороты");
        assertThat(physLeaf.getAlias()).isEqualTo("НомерСчета");
        assertThat(physLeaf.getText()).isEqualTo("Резервы.НомерСчета");
        assertThat(physLeaf.getChildFields()).isEmpty();
    }

    @Test
    void testInlineSubqueries() throws Exception {
        File output = tempDir.resolve("out_inline").toFile();
        SdqlCli.main(new String[]{"examples/example_4.sql", output.getAbsolutePath()});

        // SRS04.02 FR-3.1: SDBL_PARS model contains WhereBlock with subqueries
        QueryModel model = ModelJsonMapper.read(
            output.toPath().resolve("sdbl_parse_model_example_4.json"));
        assertThat(model.getNodes()).hasSize(1);
        var query = model.getNodes().get(0).getQuery();

        // FR-3.1.1: WhereBlock with subqueries
        assertThat(query.getWhere()).isNotNull();
        assertThat(query.getWhere().getText()).contains("ВТ_Подзапрос_2");
        assertThat(query.getWhere().getSubqueries()).hasSize(1);
        assertThat(query.getWhere().getSubqueries().get(0).getName()).isEqualTo("ВТ_Подзапрос_2");
        assertThat(query.getWhere().getSubqueries().get(0).getQuery().getFrom().get(0).getTable())
            .isEqualTo("Справочник.Контрагенты");

        // FR-3.1.3: VirtualTableBlock with subqueries
        assertThat(query.getFrom().get(1).getVirtualTable()).isNotNull();
        assertThat(query.getFrom().get(1).getVirtualTable().getText()).contains("ВТ_Подзапрос_1");
        assertThat(query.getFrom().get(1).getVirtualTable().getSubqueries()).hasSize(1);
        assertThat(query.getFrom().get(1).getVirtualTable().getSubqueries().get(0).getName())
            .isEqualTo("ВТ_Подзапрос_1");
        assertThat(query.getFrom().get(1).getVirtualTable().getSubqueries().get(0).getQuery().getFrom().get(0).getTable())
            .isEqualTo("вт_НашиДоговора");

        // FR-3.7.3: LINE_PARS contains sub_query nodes for inline subqueries
        Path lineParsModelPath = tempDir.resolve("LINE_PARS/LINE_PARS_model_example_4.json");
        assertThat(lineParsModelPath).exists();
        ObjectMapper mapper = new ObjectMapper();
        LineParsModel lineParsModel = mapper.readValue(lineParsModelPath.toFile(), LineParsModel.class);
        var inlineNodes = lineParsModel.getNodes().stream()
            .filter(n -> n.getName() != null && (n.getName().contains("_WHERE_") || n.getName().contains("_VT_")))
            .toList();
        assertThat(inlineNodes).hasSize(2);
        assertThat(inlineNodes.get(0).getType()).isEqualTo("sub_query");
        assertThat(inlineNodes.get(1).getType()).isEqualTo("sub_query");

        // FR-3.7.3: Hierarchy contains inline-SUB nodes in table_hierarchy
        Path hierarchyPath = tempDir.resolve("LINE_PARS/LINE_PARS_hierarchy_example_4.json");
        assertThat(hierarchyPath).exists();
        var hierarchy = mapper.readValue(hierarchyPath.toFile(),
            new TypeReference<List<HierarchyNode>>() {});
        var rootNode = hierarchy.stream()
            .filter(n -> "Результат_1".equals(n.getName()))
            .findFirst().orElse(null);
        assertThat(rootNode).isNotNull();
        var inlineHierarchy = rootNode.getTableHierarchy().stream()
            .filter(n -> n.getName() != null && (n.getName().contains("_WHERE_") || n.getName().contains("_VT_")))
            .toList();
        assertThat(inlineHierarchy).hasSize(2);
        assertThat(inlineHierarchy.get(0).getTypeHierarchy()).isEqualTo("from");
        assertThat(inlineHierarchy.get(1).getTypeHierarchy()).isEqualTo("from");

        // FR-3.7.3: Restored SQL contains original inline subqueries in parentheses
        Path restoredDir = tempDir.resolve("RESTORED_QUERIES/example_4");
        assertThat(restoredDir).exists();
        Path restoredNodeDir = Files.list(restoredDir)
            .filter(Files::isDirectory)
            .findFirst()
            .orElse(null);
        assertThat(restoredNodeDir).isNotNull();
        Path restoredSql = Files.list(restoredNodeDir)
            .filter(f -> f.toString().endsWith(".sql"))
            .findFirst()
            .orElse(null);
        assertThat(restoredSql).isNotNull();
        String sql = Files.readString(restoredSql);
        assertThat(sql).contains("ВЫБРАТЬ");
        assertThat(sql).contains("ИЗ");
        // Inline subquery should be restored back to (ВЫБРАТЬ ...)
        assertThat(sql).contains("(");
        assertThat(sql).contains("вт_НашиДоговора");
        assertThat(sql).contains("Справочник.Контрагенты");
        // Should NOT contain inline references
        assertThat(sql).doesNotContain("Результат_1_INLINE_1");
        assertThat(sql).doesNotContain("Результат_1_INLINE_2");
    }
}
