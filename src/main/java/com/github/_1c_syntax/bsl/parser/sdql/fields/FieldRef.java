package com.github._1c_syntax.bsl.parser.sdql.fields;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data @NoArgsConstructor
public class FieldRef {
    private String aliasTable;
    private String field;
    private String primaryTable;
}
