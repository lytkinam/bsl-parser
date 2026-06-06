package com.github._1c_syntax.bsl.parser.sdql.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SdqlPipelineImplTest {

  @TempDir
  Path tempDir;

  @Test
  void testAnalyzeInMemory() {
    String sql = """
      ВЫБРАТЬ
        1 КАК Поле1
      ПОМЕСТИТЬ ВТ_Тест
      ;
      
      ВЫБРАТЬ
        ВТ_Тест.Поле1 КАК Поле1
      ИЗ
        ВТ_Тест КАК ВТ_Тест
      """;

    SdqlPipeline pipeline = new SdqlPipelineImpl(new InMemoryModelRepository());
    PipelineResult result = pipeline.analyzeInMemory(sql, "test");

    assertThat(result.getBaseName()).isEqualTo("test");
    assertThat(result.getSdblModel()).isNotNull();
    assertThat(result.getLineParsModel()).isNotNull();
    assertThat(result.getFullParsModel()).isNotNull();
    assertThat(result.getNodesCount()).isGreaterThan(0);
  }

  @Test
  void testAnalyzeWithFiles() {
    String sql = """
      ВЫБРАТЬ
        1 КАК Поле1
      ПОМЕСТИТЬ ВТ_Тест
      ;
      
      ВЫБРАТЬ
        ВТ_Тест.Поле1 КАК Поле1
      ИЗ
        ВТ_Тест КАК ВТ_Тест
      """;

    Path outputDir = tempDir.resolve("out");
    SdqlPipeline pipeline = new SdqlPipelineImpl(new FileSystemModelRepository(outputDir));
    PipelineResult result = pipeline.analyze(sql, outputDir, "test", false);

    assertThat(result.getBaseName()).isEqualTo("test");
    assertThat(result.getSdblParsPath()).exists();
    assertThat(result.getLineParsPath()).exists();
    assertThat(result.getFullParsPath()).exists();
  }

  @Test
  void testAutoBaseName() {
    String sql = "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест";

    SdqlPipeline pipeline = new SdqlPipelineImpl(new InMemoryModelRepository());
    PipelineResult result = pipeline.analyzeInMemory(sql, null);

    assertThat(result.getBaseName()).startsWith("tmp_");
  }
}
