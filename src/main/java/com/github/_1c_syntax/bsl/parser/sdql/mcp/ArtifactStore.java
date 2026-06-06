package com.github._1c_syntax.bsl.parser.sdql.mcp;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Manages artifact persistence between temporary (Online) and permanent (Offline) storage.
 * <p>
 * After Online analysis, artifacts are created in a temporary directory.
 * This class copies them to the repository's baseDir so they become available
 * for Offline access via the same baseName.
 * </p>
 */
public class ArtifactStore {

  private final Path baseDir;

  public ArtifactStore(Path baseDir) {
    this.baseDir = baseDir;
  }

  /**
   * Copy all artifacts from a temporary analysis directory into the permanent repository.
   *
   * @param tempDir  Temporary directory where pipeline wrote artifacts
   * @param baseName Base name of the analyzed package
   */
  public void persist(Path tempDir, String baseName) {
    if (tempDir == null || !Files.exists(tempDir)) {
      return;
    }
    try {
      // Copy SDBL_PARS
      copyDir(tempDir.resolve("SDBL_PARS"), baseDir.resolve("SDBL_PARS"));
      // Copy sdbl_parse_nodes (may be in tempDir root or SDBL_PARS)
      copyFile(tempDir.resolve("sdbl_parse_nodes_" + baseName + ".json"),
        baseDir.resolve("sdbl_parse_nodes_" + baseName + ".json"));

      // Copy LINE_PARS
      Path lineParsSrc = tempDir.getParent() != null
        ? tempDir.getParent().resolve("LINE_PARS")
        : tempDir.resolve("LINE_PARS");
      if (Files.exists(lineParsSrc)) {
        copyDir(lineParsSrc, baseDir.resolve("LINE_PARS"));
      }

      // Copy FULL_PARS
      Path fullParsSrc = tempDir.getParent() != null
        ? tempDir.getParent().resolve("FULL_PARS")
        : tempDir.resolve("FULL_PARS");
      if (Files.exists(fullParsSrc)) {
        copyDir(fullParsSrc, baseDir.resolve("FULL_PARS"));
      }

      // Copy field_lineage
      Path flSrc = tempDir.getParent() != null
        ? tempDir.getParent().resolve("field_lineage")
        : tempDir.resolve("field_lineage");
      if (Files.exists(flSrc)) {
        copyDir(flSrc, baseDir.resolve("field_lineage"));
      }

      // Copy full_field_lineage
      Path fflSrc = tempDir.getParent() != null
        ? tempDir.getParent().resolve("full_field_lineage")
        : tempDir.resolve("full_field_lineage");
      if (Files.exists(fflSrc)) {
        copyDir(fflSrc, baseDir.resolve("full_field_lineage"));
      }

      // Copy RESTORED_QUERIES
      Path rqSrc = tempDir.getParent() != null
        ? tempDir.getParent().resolve("RESTORED_QUERIES")
        : tempDir.resolve("RESTORED_QUERIES");
      if (Files.exists(rqSrc)) {
        copyDir(rqSrc, baseDir.resolve("RESTORED_QUERIES"));
      }

      // Copy EXTRACTED_QUERIES
      Path eqSrc = tempDir.getParent() != null
        ? tempDir.getParent().resolve("EXTRACTED_QUERIES")
        : tempDir.resolve("EXTRACTED_QUERIES");
      if (Files.exists(eqSrc)) {
        copyDir(eqSrc, baseDir.resolve("EXTRACTED_QUERIES"));
      }

      // Copy VERIFICATION
      Path verSrc = tempDir.getParent() != null
        ? tempDir.getParent().resolve("VERIFICATION")
        : tempDir.resolve("VERIFICATION");
      if (Files.exists(verSrc)) {
        copyDir(verSrc, baseDir.resolve("VERIFICATION"));
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to persist artifacts for " + baseName, e);
    }
  }

  private void copyDir(Path src, Path dst) throws IOException {
    if (!Files.exists(src)) {
      return;
    }
    Files.createDirectories(dst);
    try (Stream<Path> stream = Files.walk(src)) {
      for (Path sourcePath : stream.toList()) {
        Path relative = src.relativize(sourcePath);
        Path targetPath = dst.resolve(relative);
        if (Files.isDirectory(sourcePath)) {
          Files.createDirectories(targetPath);
        } else {
          Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private void copyFile(Path src, Path dst) throws IOException {
    if (src == null || !Files.exists(src)) {
      return;
    }
    Files.createDirectories(dst.getParent());
    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Delete a temporary directory recursively.
   */
  public static void deleteTempDir(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.delete(p);
        } catch (IOException e) {
          // ignore
        }
      });
    } catch (IOException e) {
      // ignore
    }
  }
}
