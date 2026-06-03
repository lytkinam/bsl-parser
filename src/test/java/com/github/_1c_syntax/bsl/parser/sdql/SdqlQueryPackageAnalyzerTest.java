package com.github._1c_syntax.bsl.parser.sdql;

import com.github._1c_syntax.bsl.parser.sdql.io.ModelJsonMapper;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

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
        assertThat(model.getEdges()).hasSize(2);

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
    }

    @Test
    void testExample258Sql() throws Exception {
        File output = tempDir.resolve("out258").toFile();
        SdqlCli.main(new String[]{"examples/example_258.sql", output.getAbsolutePath()});

        QueryModel model = ModelJsonMapper.read(
            output.toPath().resolve("sdbl_parse_model_example_258.json"));
        assertThat(model.getNodes()).hasSizeGreaterThan(100);
        assertThat(model.getEdges()).hasSizeGreaterThan(100);

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
    }
}
