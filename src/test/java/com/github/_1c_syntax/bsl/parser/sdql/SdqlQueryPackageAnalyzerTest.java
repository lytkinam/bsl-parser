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

        // query texts exported from nodes.json
        assertThat(output.toPath().resolve("query_texts_example")).exists();
        assertThat(output.toPath().resolve("query_texts_example/node_0.sql")).exists();
        assertThat(output.toPath().resolve("query_texts_example/node_1.sql")).exists();

        // fields and lineage
        assertThat(output.toPath().resolve("fields_node_example")).exists();
        assertThat(output.toPath().resolve("lineage_example")).exists();

        // LINE_PARS model and hierarchy (created as sibling to outputDir)
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_model_example.json")).exists();
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_hierarchy_example.json")).exists();

        // field_lineage as sibling to LINE_PARS, per-node per-field files
        Path fieldLineageDir = tempDir.resolve("field_lineage_example");
        assertThat(fieldLineageDir).exists();
        // example.sql has result node id=2 (Результат_3)
        Path nodeDir = fieldLineageDir.resolve("2_Результат_3");
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

        // Check field lineage
        String lineageJson = Files.readString(output.toPath().resolve("lineage_example_258/field_lineage.json"));
        assertThat(lineageJson).contains("ВидОбязательств_гр1а");

        // Check all query texts exist
        for (int i = 0; i < model.getNodes().size(); i++) {
            assertThat(output.toPath().resolve("query_texts_example_258/node_" + i + ".sql")).exists();
        }

        // LINE_PARS model and hierarchy (created as sibling to outputDir)
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_model_example_258.json")).exists();
        assertThat(tempDir.resolve("LINE_PARS/LINE_PARS_hierarchy_example_258.json")).exists();

        // field_lineage as sibling to LINE_PARS, per-node per-field files
        Path fieldLineageDir258 = tempDir.resolve("field_lineage_example_258");
        assertThat(fieldLineageDir258).exists();
        assertThat(fieldLineageDir258).isDirectory();
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
        assertThat(union1.getId()).isEqualTo(27);
        assertThat(union1.getText()).isEqualTo("уп_РезервыОбороты.НомерСчета");
        assertThat(union1.getChildFields()).hasSize(1);
        FieldLineageNode leaf1 = union1.getChildFields().get(0);
        assertThat(leaf1.getChildName()).isEqualTo("уп_РезервыОбороты");
        assertThat(leaf1.getSource()).contains("РегистрНакопления.уп_Резервы.Обороты");
        assertThat(leaf1.getAlias()).isEqualTo("НомерСчета");

        // 2. Union part with chain of fields — must NOT produce false child
        FieldLineageNode chainResult = extractor.extract(39, "ПенсионныйСчет");
        assertThat(chainResult).isNotNull();
        assertThat(chainResult.getId()).isEqualTo(39);
        assertThat(chainResult.getText()).isEqualTo("ВТ_ПенсионныеСчета_ПР.ПенсионныйСчет.Владелец.ПенсионныйСчет");
        // Should have exactly one child (ВТ_ПенсионныеСчета_ПР), not two
        assertThat(chainResult.getChildFields()).hasSize(1);
        assertThat(chainResult.getChildFields().get(0).getChildName()).isEqualTo("ВТ_ПенсионныеСчета_ПР");
        assertThat(chainResult.getChildFields().get(0).getId()).isEqualTo(24);

        // 3. Physical table leaf
        FieldLineageNode leafResult = extractor.extract(42, "ПенсионныйСчет");
        assertThat(leafResult).isNotNull();
        assertThat(leafResult.getId()).isEqualTo(42);
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
}
