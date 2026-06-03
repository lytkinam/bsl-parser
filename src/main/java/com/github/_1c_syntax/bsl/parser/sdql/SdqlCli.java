package com.github._1c_syntax.bsl.parser.sdql;

import com.github._1c_syntax.bsl.parser.sdql.export.QueryTextExporter;
import com.github._1c_syntax.bsl.parser.sdql.fields.FieldsNodeBuilder;
import com.github._1c_syntax.bsl.parser.sdql.lineage.FieldLineageAnalyzer;

import java.io.File;
import java.nio.file.Path;

public class SdqlCli {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SdqlCli <input.sql> <output_dir> [--detailed]");
            System.exit(1);
        }
        boolean detailed = args.length > 2 && "--detailed".equals(args[2]);
        File input = new File(args[0]);
        File outputDir = new File(args[1]);
        outputDir.mkdirs();

        // 1. Primary analysis: nodes.json (full text) + model.json (stripped text)
        SdqlQueryPackageAnalyzer analyzer = new SdqlQueryPackageAnalyzer();
        analyzer.analyze(input, outputDir);

        // 2. Export query texts from primary nodes.json
        QueryTextExporter textExporter = new QueryTextExporter();
        textExporter.export(analyzer.getFullNodes(), outputDir.toPath());

        // 3. Build fields node from model.json
        FieldsNodeBuilder fieldsBuilder = new FieldsNodeBuilder();
        fieldsBuilder.build(analyzer.getModel(), outputDir.toPath());

        // 4. Lineage analysis from model.json
        FieldLineageAnalyzer lineageAnalyzer = new FieldLineageAnalyzer();
        lineageAnalyzer.analyze(analyzer.getModel(), outputDir.toPath());

        System.out.println("Done: " + outputDir.getAbsolutePath());
    }
}
