package com.github._1c_syntax.bsl.parser.sdql.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github._1c_syntax.bsl.parser.sdql.model.QueryNode;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class NodesJsonMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static void write(List<QueryNode> nodes, File file) throws IOException {
        MAPPER.writeValue(file, nodes);
    }
}
