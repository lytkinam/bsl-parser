package com.github._1c_syntax.bsl.parser.sdql.fields;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data @NoArgsConstructor
public class TableAlias {
    private String alias;
    private String primaryTable;
    private boolean virtual;
    private String joinType;
}
