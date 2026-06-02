package com.github._1c_syntax.bsl.parser.sdql.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryModel;

import java.io.IOException;
import java.nio.file.Path;

public class ModelJsonMapper {
  private static final ObjectMapper MAPPER = new ObjectMapper()
    .enable(SerializationFeature.INDENT_OUTPUT);

  public static void write(QueryModel model, Path path) throws IOException {
    MAPPER.writeValue(path.toFile(), model);
  }

  public static QueryModel read(Path path) throws IOException {
    return MAPPER.readValue(path.toFile(), QueryModel.class);
  }
}
