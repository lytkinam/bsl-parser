package com.github._1c_syntax.bsl.parser.sdql;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullFieldLineageBuilder;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModelBuilder;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsFieldLineageBuilder;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsHierarchyBuilder;
import com.github._1c_syntax.bsl.parser.sdql.line_pars.LineParsModelBuilder;
import com.github._1c_syntax.bsl.parser.sdql.md.HierarchyMdBuilder;
import com.github._1c_syntax.bsl.parser.sdql.md.LineParsFieldLineageMdBuilder;
import com.github._1c_syntax.bsl.parser.sdql.md.LineParsModelMdBuilder;
import com.github._1c_syntax.bsl.parser.sdql.md.SdqlModelMdBuilder;

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

        String baseName = baseName(input);

        // 1. Primary analysis: model.json (stripped text)
        SdqlQueryPackageAnalyzer analyzer = new SdqlQueryPackageAnalyzer();
        analyzer.analyze(input, outputDir, baseName);

        // 2. Build LINE_PARS model (subqueries, unions, expanded edges)
        LineParsModelBuilder lineParsBuilder = new LineParsModelBuilder();
        java.nio.file.Path lineParsDir = lineParsBuilder.build(outputDir.toPath(), baseName);

        // 3. Build LINE_PARS hierarchy extraction
        LineParsHierarchyBuilder hierarchyBuilder = new LineParsHierarchyBuilder();
        hierarchyBuilder.build(lineParsDir, baseName);

        // 4. Build LINE_PARS field lineage (target nodes only)
        LineParsFieldLineageBuilder fieldLineageBuilder = new LineParsFieldLineageBuilder();
        fieldLineageBuilder.build(outputDir.toPath(), baseName);

        // 5. Build FULL_PARS model (field_id + child_fields)
        FullParsModelBuilder fullParsBuilder = new FullParsModelBuilder();
        java.nio.file.Path fullParsDir = fullParsBuilder.build(lineParsDir, baseName);

        // 6. Build full_field_lineage for all fields of target nodes (result or last temp_query)
        FullFieldLineageBuilder fflBuilder = new FullFieldLineageBuilder();
        fflBuilder.build(fullParsDir, baseName);

        // Generate Markdown reports from JSON artifacts
        new SdqlModelMdBuilder().build(outputDir.toPath(), baseName);
        new LineParsModelMdBuilder().build(lineParsDir, baseName);
        new HierarchyMdBuilder().build(lineParsDir, baseName);

        // Generate Markdown for LINE_PARS field lineage
        java.nio.file.Path fieldLineageDir = outputDir.toPath().getParent().resolve("field_lineage");
        new LineParsFieldLineageMdBuilder().build(fieldLineageDir, baseName);

        System.out.println("Done: " + outputDir.getAbsolutePath());
    }

    private static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
