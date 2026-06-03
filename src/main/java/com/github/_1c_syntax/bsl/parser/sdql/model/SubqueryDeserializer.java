package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public class SubqueryDeserializer extends JsonDeserializer<Object> {
  @Override
  public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    if (p.currentToken() == JsonToken.VALUE_STRING) {
      return p.getValueAsString();
    }
    return p.readValueAs(QueryAst.class);
  }
}
