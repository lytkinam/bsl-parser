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

        QueryModel model = ModelJsonMapper.read(
            output.toPath().resolve("model.json"));
        assertThat(model.getNodes()).hasSize(5);
        assertThat(model.getEdges()).hasSize(2);
        assertThat(output.toPath().resolve("query_texts")).exists();
        assertThat(output.toPath().resolve("fields_node")).exists();
        assertThat(output.toPath().resolve("lineage")).exists();
    }

    @Test
    void testExample258Sql() throws Exception {
        File output = tempDir.resolve("out258").toFile();
        SdqlCli.main(new String[]{"examples/example_258.sql", output.getAbsolutePath()});

        QueryModel model = ModelJsonMapper.read(
            output.toPath().resolve("model.json"));
        assertThat(model.getNodes()).hasSizeGreaterThan(100);
        assertThat(model.getEdges()).hasSizeGreaterThan(100);

        // Check field lineage for ВидОбязательств_гр1а
        String lineageJson = Files.readString(output.toPath().resolve("lineage/field_lineage.json"));
        assertThat(lineageJson).contains("ВидОбязательств_гр1а");
    }
}
