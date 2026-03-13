package com.github.zomboiddecompiler;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * CLI tool that applies PostDecompileTransforms to all .java files in a directory.
 * Used to process files decompiled by raw Vineflower (se/, com/, etc.)
 * that don't go through ZomboidResultSaver.
 */
public final class TransformFiles {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: TransformFiles <directory>");
            System.exit(1);
        }

        Path dir = Paths.get(args[0]);
        if (!Files.isDirectory(dir)) {
            System.err.println("Not a directory: " + dir);
            System.exit(1);
        }

        int[] count = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    String content = Files.readString(file);
                    String transformed = PostDecompileTransforms.apply(content);
                    if (!transformed.equals(content)) {
                        Files.writeString(file, transformed);
                        count[0]++;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        System.out.println("Transformed " + count[0] + " files in " + dir);
    }
}
