package com.github._1c_syntax.bsl.parser.sdql.fields;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data @NoArgsConstructor
public class FieldRecord {
    private String alias;
    private String expressionRaw;
    private String exprType;
    private List<FieldRef> fieldRefs;
}
