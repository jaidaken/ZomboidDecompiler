package com.github.zomboiddecompiler.commands;

import com.github.zomboiddecompiler.verify.BytecodeComparator;
import com.github.zomboiddecompiler.verify.BytecodeComparator.ClassResult;
import com.github.zomboiddecompiler.verify.BytecodeComparator.MethodResult;
import com.github.zomboiddecompiler.verify.BytecodeComparator.Status;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * CLI command that compares original bytecode against recompiled classes
 * to verify decompilation correctness at the instruction level.
 */
@Command(name = "verify", mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Compares original bytecode against recompiled classes to detect decompilation errors.")
public class Verify implements Callable<Integer> {

    @Parameters(index = "0", description = "Original JAR or classes directory")
    private Path originalPath;

    @Parameters(index = "1", description = "Recompiled JAR or classes directory")
    private Path recompiledPath;

    @Option(names = "--class-pattern", description = "Class name pattern filter (e.g., zombie.*)")
    private String classPattern = "zombie.*";

    @Option(names = "--verbose", description = "Show all methods including matches")
    private boolean verbose;

    @Option(names = "--strict-vars", description = "Compare variable indices directly without normalization")
    private boolean strictVars;

    @Option(names = "--context", description = "Number of instructions to show around diffs (default: 5)")
    private int context = 5;

    @Option(names = "--no-color", description = "Disable ANSI color output")
    private boolean noColor;

    @Option(names = "--summary-only", description = "Only show summary statistics, no per-method details")
    private boolean summaryOnly;

    @Option(names = "--semantic", description = "Enable semantic normalization (DUP/store-load-return/GOTO-to-return)")
    private boolean semantic;

    // ANSI color codes
    private String red(String s) { return noColor ? s : "\u001b[31m" + s + "\u001b[0m"; }
    private String green(String s) { return noColor ? s : "\u001b[32m" + s + "\u001b[0m"; }
    private String yellow(String s) { return noColor ? s : "\u001b[33m" + s + "\u001b[0m"; }
    private String cyan(String s) { return noColor ? s : "\u001b[36m" + s + "\u001b[0m"; }
    private String bold(String s) { return noColor ? s : "\u001b[1m" + s + "\u001b[0m"; }
    private String dim(String s) { return noColor ? s : "\u001b[2m" + s + "\u001b[0m"; }

    @Override
    public Integer call() {
        if (!Files.exists(originalPath)) {
            System.err.println("Original path does not exist: " + originalPath);
            return 1;
        }
        if (!Files.exists(recompiledPath)) {
            System.err.println("Recompiled path does not exist: " + recompiledPath);
            return 1;
        }

        Predicate<String> filter = BytecodeComparator.createClassFilter(classPattern);
        BytecodeComparator comparator = new BytecodeComparator(!strictVars, semantic, context);

        System.out.println(bold("Bytecode Verification"));
        System.out.println(dim("Original:    " + originalPath));
        System.out.println(dim("Recompiled:  " + recompiledPath));
        System.out.println(dim("Pattern:     " + classPattern));
        System.out.println(dim("Var mode:    " + (strictVars ? "strict" : "normalized")));
        System.out.println(dim("Semantic:    " + (semantic ? "enabled" : "disabled")));
        System.out.println();

        // Load classes
        Map<String, ClassResult> results;
        try {
            results = loadAndCompare(comparator, filter);
        } catch (IOException e) {
            System.err.println("Error loading classes: " + e.getMessage());
            return 1;
        }

        // Print report
        printReport(results);

        // Return non-zero if any mismatches
        boolean hasMismatches = results.values().stream()
                .anyMatch(r -> r.status() != Status.MATCH);
        return hasMismatches ? 2 : 0;
    }

    private Map<String, ClassResult> loadAndCompare(BytecodeComparator comparator,
                                                     Predicate<String> filter) throws IOException {
        System.out.print("Loading original classes...");
        Map<String, org.objectweb.asm.tree.ClassNode> origClasses =
                BytecodeComparator.loadClasses(originalPath, filter);
        System.out.println(" " + origClasses.size() + " classes");

        System.out.print("Loading recompiled classes...");
        Map<String, org.objectweb.asm.tree.ClassNode> recompClasses =
                BytecodeComparator.loadClasses(recompiledPath, filter);
        System.out.println(" " + recompClasses.size() + " classes");
        System.out.println();

        // Compare classes that exist in both sets
        Map<String, ClassResult> results = new LinkedHashMap<>();

        Set<String> allClasses = new TreeSet<>();
        allClasses.addAll(origClasses.keySet());
        allClasses.addAll(recompClasses.keySet());

        int compared = 0;
        for (String className : allClasses) {
            var orig = origClasses.get(className);
            var recomp = recompClasses.get(className);

            if (orig == null) {
                results.put(className, new ClassResult(className, Status.MISSING_ORIG,
                        List.of("Class exists only in recompiled"), List.of()));
            } else if (recomp == null) {
                results.put(className, new ClassResult(className, Status.MISSING_RECOMP,
                        List.of("Class exists only in original"), List.of()));
            } else {
                results.put(className, comparator.compareClass(className, orig, recomp));
                compared++;
            }
        }

        System.out.println("Compared " + compared + " classes");
        System.out.println();

        return results;
    }

    private void printReport(Map<String, ClassResult> results) {
        // Summary statistics
        long totalClasses = results.size();
        long matchClasses = results.values().stream().filter(r -> r.status() == Status.MATCH).count();
        long mismatchClasses = results.values().stream().filter(r -> r.status() == Status.MISMATCH).count();
        long missingOrigClasses = results.values().stream().filter(r -> r.status() == Status.MISSING_ORIG).count();
        long missingRecompClasses = results.values().stream().filter(r -> r.status() == Status.MISSING_RECOMP).count();

        long totalMethods = results.values().stream().flatMap(r -> r.methods().stream()).count();
        long matchMethods = results.values().stream().flatMap(r -> r.methods().stream())
                .filter(m -> m.status() == Status.MATCH).count();
        long mismatchMethods = results.values().stream().flatMap(r -> r.methods().stream())
                .filter(m -> m.status() == Status.MISMATCH).count();
        long missingMethods = results.values().stream().flatMap(r -> r.methods().stream())
                .filter(m -> m.status() == Status.MISSING_ORIG || m.status() == Status.MISSING_RECOMP).count();

        double classRate = totalClasses > 0 ? 100.0 * matchClasses / totalClasses : 0;
        double methodRate = totalMethods > 0 ? 100.0 * matchMethods / totalMethods : 0;

        System.out.println(bold("=== SUMMARY ==="));
        System.out.printf("Classes:  %d total, %s match (%s), %s mismatch",
                totalClasses,
                green(String.valueOf(matchClasses)),
                green(String.format("%.1f%%", classRate)),
                mismatchClasses > 0 ? red(String.valueOf(mismatchClasses)) : "0");
        if (missingOrigClasses > 0) System.out.printf(", %s extra", yellow(String.valueOf(missingOrigClasses)));
        if (missingRecompClasses > 0) System.out.printf(", %s missing", yellow(String.valueOf(missingRecompClasses)));
        System.out.println();

        System.out.printf("Methods:  %d total, %s match (%s), %s mismatch",
                totalMethods,
                green(String.valueOf(matchMethods)),
                green(String.format("%.1f%%", methodRate)),
                mismatchMethods > 0 ? red(String.valueOf(mismatchMethods)) : "0");
        if (missingMethods > 0) System.out.printf(", %s missing/extra", yellow(String.valueOf(missingMethods)));
        System.out.println();
        System.out.println();

        if (summaryOnly) {
            return;
        }

        // Detailed results for non-matching classes
        List<ClassResult> mismatches = results.values().stream()
                .filter(r -> r.status() != Status.MATCH)
                .toList();

        if (mismatches.isEmpty()) {
            System.out.println(green("All classes match!"));
            return;
        }

        System.out.println(bold("=== MISMATCHES ==="));
        System.out.println();

        for (ClassResult cr : mismatches) {
            printClassResult(cr);
        }
    }

    private void printClassResult(ClassResult cr) {
        String className = cr.name().replace('/', '.');

        // Only show missing/extra classes in verbose mode
        if (cr.status() == Status.MISSING_ORIG) {
            if (verbose) {
                System.out.println(yellow("+ " + className) + dim(" (extra in recompiled)"));
            }
            return;
        }
        if (cr.status() == Status.MISSING_RECOMP) {
            if (verbose) {
                System.out.println(yellow("- " + className) + dim(" (missing from recompiled)"));
            }
            return;
        }

        long methodMatch = cr.matchCount();
        long methodTotal = cr.methods().size();
        long methodMismatch = cr.mismatchCount();

        System.out.println(red(className)
                + dim(" (" + methodMismatch + "/" + methodTotal + " methods differ)"));

        // Structure diffs
        for (String diff : cr.structureDiffs()) {
            System.out.println("    " + yellow(diff));
        }

        // Method diffs
        for (MethodResult mr : cr.methods()) {
            if (mr.status() == Status.MATCH && !verbose) {
                continue;
            }
            printMethodResult(mr);
        }
        System.out.println();
    }

    private void printMethodResult(MethodResult mr) {
        String signature = mr.name() + mr.descriptor();

        if (mr.status() == Status.MATCH) {
            System.out.println("    " + green("  " + signature));
            return;
        }

        if (mr.status() == Status.MISSING_ORIG) {
            System.out.println("    " + yellow("+ " + signature) + dim(" (extra)"));
            return;
        }
        if (mr.status() == Status.MISSING_RECOMP) {
            System.out.println("    " + yellow("- " + signature) + dim(" (missing)"));
            return;
        }

        // Mismatch
        String counts = mr.origInsnCount() + " vs " + mr.recompInsnCount() + " insns";
        System.out.println("    " + red("x " + signature)
                + dim(" -- " + counts + ", " + mr.diffDescription()));

        if (!mr.origContext().isEmpty() || !mr.recompContext().isEmpty()) {
            // Find the common prefix in context lines (before the diff point)
            int diffIdx = mr.firstDiffIndex();
            int ctxStart = Math.max(0, diffIdx - context);

            // Print common context (instructions before the diff that match)
            int commonLines = diffIdx - ctxStart;
            if (commonLines > 0 && !mr.origContext().isEmpty()) {
                for (int i = 0; i < commonLines && i < mr.origContext().size(); i++) {
                    System.out.println("        " + dim(mr.origContext().get(i)));
                }
            }

            // Print diverging instructions
            if (!mr.origContext().isEmpty()) {
                System.out.println("      " + red("--- original ---"));
                for (int i = commonLines; i < mr.origContext().size(); i++) {
                    System.out.println("        " + red(mr.origContext().get(i)));
                }
            }
            if (!mr.recompContext().isEmpty()) {
                System.out.println("      " + cyan("--- recompiled ---"));
                for (int i = commonLines; i < mr.recompContext().size(); i++) {
                    System.out.println("        " + cyan(mr.recompContext().get(i)));
                }
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Verify()).execute(args);
        System.exit(exitCode);
    }
}
