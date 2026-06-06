package com.github._1c_syntax.bsl.parser.sdql;

import com.github._1c_syntax.bsl.parser.sdql.api.FileSystemModelRepository;
import com.github._1c_syntax.bsl.parser.sdql.api.PipelineResult;
import com.github._1c_syntax.bsl.parser.sdql.api.SdqlPipeline;
import com.github._1c_syntax.bsl.parser.sdql.api.SdqlPipelineImpl;

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
        String sqlText = java.nio.file.Files.readString(input.toPath());

        SdqlPipeline pipeline = new SdqlPipelineImpl(
            new FileSystemModelRepository(outputDir.toPath())
        );
        PipelineResult result = pipeline.analyze(sqlText, outputDir.toPath(), baseName, detailed);

        System.out.println("Done: " + outputDir.getAbsolutePath());
        System.out.println("Base name: " + result.getBaseName());
        System.out.println("Nodes: " + result.getNodesCount());
    }

    private static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
