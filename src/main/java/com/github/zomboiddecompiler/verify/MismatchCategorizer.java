package com.github.zomboiddecompiler.verify;

import com.github.zomboiddecompiler.verify.BytecodeComparator.MethodResult;
import com.github.zomboiddecompiler.verify.BytecodeComparator.Status;

import java.util.*;

/**
 * Classifies method bytecode mismatches into actionable categories
 * based on the instruction context around the first divergence point.
 */
public final class MismatchCategorizer {

    public enum Category {
        GUARD_CLAUSE_INVERSION("Guard clause inversion",
                "Original has early-exit pattern (IF→RETURN), recompiled has inverted condition to body"),
        LABEL_ONLY("Label-only difference",
                "Same instructions but different branch target labels"),
        EXPRESSION_REORDER("Expression reorder",
                "Commutative operations evaluated in different order"),
        CONSTANT_PROPAGATION("Constant propagation",
                "GETSTATIC/load vs inline constant (compiler propagated a known value)"),
        STRING_CONCAT("String concatenation",
                "Different makeConcatWithConstants or StringBuilder patterns"),
        DUP_PATTERN("DUP pattern difference",
                "Extra or missing DUP instruction in stack manipulation"),
        NARROWING_CAST("Narrowing cast",
                "Extra or missing I2B/I2S/I2C narrowing cast"),
        CHECKCAST_DIFF("CHECKCAST difference",
                "Extra or missing CHECKCAST instruction (generics erasure)"),
        SWITCH_DIFF("Switch difference",
                "TABLESWITCH vs LOOKUPSWITCH or case ordering difference"),
        TRY_CATCH_DIFF("Try-catch difference",
                "Different try-catch block structure"),
        LAMBDA_SYNTHETIC("Lambda/synthetic method",
                "Lambda or synthetic bridge method difference"),
        INSN_COUNT_DIFF("Instruction count difference",
                "Significantly different instruction counts (structural decompilation error)"),
        BOXING_ROUNDTRIP("Boxing round-trip",
                "Extra valueOf/xxxValue boxing/unboxing pairs"),
        REQUIRE_NONNULL("Objects.requireNonNull",
                "Extra or missing Objects.requireNonNull + POP pattern"),
        INVOKE_DISPATCH("Invoke dispatch difference",
                "INVOKEVIRTUAL vs INVOKEINTERFACE or different owner class"),
        CONSTANT_ENCODING("Constant encoding",
                "Same value loaded with different opcode (ICONST vs BIPUSH vs LDC)"),
        OTHER("Other / unclassified",
                "Mismatch does not match a known pattern");

        private final String label;
        private final String description;

        Category(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() { return label; }
        public String description() { return description; }
    }

    public record CategorizedMethod(Category category, MethodResult result, String className) {}

    private MismatchCategorizer() {}

    /**
     * Categorize a single mismatched method based on its context lines.
     */
    public static Category categorize(MethodResult mr) {
        if (mr.status() != Status.MISMATCH) {
            throw new IllegalArgumentException("Only MISMATCH methods can be categorized");
        }

        List<String> orig = mr.origContext();
        List<String> recomp = mr.recompContext();
        String desc = mr.diffDescription();

        // Try-catch difference is reported directly in the description
        if (desc != null && desc.contains("Try-catch blocks differ")) {
            return Category.TRY_CATCH_DIFF;
        }

        // Large instruction count difference suggests structural decompilation error
        int origCount = mr.origInsnCount();
        int recompCount = mr.recompInsnCount();
        if (origCount > 0 && recompCount > 0) {
            double ratio = (double) Math.max(origCount, recompCount) / Math.min(origCount, recompCount);
            if (ratio > 1.5 && Math.abs(origCount - recompCount) > 20) {
                return Category.INSN_COUNT_DIFF;
            }
        }

        // Analyze the context lines around the divergence
        String origDiff = getFirstDivergentLine(orig, mr.firstDiffIndex());
        String recompDiff = getFirstDivergentLine(recomp, mr.firstDiffIndex());

        if (origDiff == null || recompDiff == null) {
            return Category.OTHER;
        }

        // Guard clause inversion: IF_xxx vs IF_inverted_xxx at same position
        if (isGuardClauseInversion(orig, recomp, mr.firstDiffIndex())) {
            return Category.GUARD_CLAUSE_INVERSION;
        }

        // Label-only difference: instructions are the same except label IDs
        if (isLabelOnlyDifference(origDiff, recompDiff)) {
            return Category.LABEL_ONLY;
        }

        // String concatenation differences
        if (containsStringConcat(origDiff) || containsStringConcat(recompDiff)) {
            return Category.STRING_CONCAT;
        }

        // DUP pattern differences
        if (origDiff.contains("DUP") || recompDiff.contains("DUP")) {
            return Category.DUP_PATTERN;
        }

        // Narrowing cast differences
        if (isNarrowingCast(origDiff) || isNarrowingCast(recompDiff)) {
            return Category.NARROWING_CAST;
        }

        // CHECKCAST differences
        if (origDiff.contains("CHECKCAST") || recompDiff.contains("CHECKCAST")) {
            return Category.CHECKCAST_DIFF;
        }

        // Switch differences
        if (origDiff.contains("SWITCH") || recompDiff.contains("SWITCH")) {
            return Category.SWITCH_DIFF;
        }

        // Boxing round-trip
        if (isBoxingCall(origDiff) || isBoxingCall(recompDiff)) {
            return Category.BOXING_ROUNDTRIP;
        }

        // Objects.requireNonNull
        if (origDiff.contains("requireNonNull") || recompDiff.contains("requireNonNull")) {
            return Category.REQUIRE_NONNULL;
        }

        // Constant propagation: one side loads field, other pushes constant
        if (isConstantPropagation(origDiff, recompDiff)) {
            return Category.CONSTANT_PROPAGATION;
        }

        // Constant encoding: same type of push but different opcode
        if (isConstantEncodingDiff(origDiff, recompDiff)) {
            return Category.CONSTANT_ENCODING;
        }

        // Expression reorder: same opcodes in different order
        if (isExpressionReorder(orig, recomp, mr.firstDiffIndex())) {
            return Category.EXPRESSION_REORDER;
        }

        // Invoke dispatch differences
        if (isInvokeDispatchDiff(origDiff, recompDiff)) {
            return Category.INVOKE_DISPATCH;
        }

        return Category.OTHER;
    }

    /**
     * Extract the first divergent instruction from context.
     * Context includes common prefix + divergent lines; the divergent
     * ones start after (firstDiffIndex - contextStart) common lines.
     */
    private static String getFirstDivergentLine(List<String> context, int firstDiffIndex) {
        if (context == null || context.isEmpty()) return null;
        // The context list contains lines starting from (firstDiffIndex - contextSize).
        // Lines are formatted as "#N   INSTRUCTION". The first divergent line
        // is the one at index corresponding to firstDiffIndex.
        for (String line : context) {
            String stripped = line.strip();
            if (stripped.startsWith("#" + firstDiffIndex + " ") ||
                stripped.startsWith("#" + firstDiffIndex + "\t")) {
                return extractInstruction(stripped);
            }
        }
        // Fallback: use the middle line as divergence point
        if (context.size() > 1) {
            return extractInstruction(context.get(context.size() / 2).strip());
        }
        return extractInstruction(context.get(0).strip());
    }

    private static String extractInstruction(String contextLine) {
        // Remove the "#N   " prefix
        int space = contextLine.indexOf(' ');
        if (space >= 0 && contextLine.startsWith("#")) {
            return contextLine.substring(space).strip();
        }
        return contextLine;
    }

    private static boolean isGuardClauseInversion(List<String> orig, List<String> recomp, int diffIdx) {
        String origInsn = getFirstDivergentLine(orig, diffIdx);
        String recompInsn = getFirstDivergentLine(recomp, diffIdx);
        if (origInsn == null || recompInsn == null) return false;

        // Check if one is an IF and the other is the inverted IF
        return areInvertedConditions(origInsn, recompInsn);
    }

    private static boolean areInvertedConditions(String a, String b) {
        // Strip label from conditionals to compare just the opcode
        String opcA = a.split("\\s+")[0];
        String opcB = b.split("\\s+")[0];

        Map<String, String> inversions = Map.ofEntries(
                Map.entry("IFEQ", "IFNE"), Map.entry("IFNE", "IFEQ"),
                Map.entry("IFLT", "IFGE"), Map.entry("IFGE", "IFLT"),
                Map.entry("IFGT", "IFLE"), Map.entry("IFLE", "IFGT"),
                Map.entry("IFNULL", "IFNONNULL"), Map.entry("IFNONNULL", "IFNULL"),
                Map.entry("IF_ICMPEQ", "IF_ICMPNE"), Map.entry("IF_ICMPNE", "IF_ICMPEQ"),
                Map.entry("IF_ICMPLT", "IF_ICMPGE"), Map.entry("IF_ICMPGE", "IF_ICMPLT"),
                Map.entry("IF_ICMPGT", "IF_ICMPLE"), Map.entry("IF_ICMPLE", "IF_ICMPGT"),
                Map.entry("IF_ACMPEQ", "IF_ACMPNE"), Map.entry("IF_ACMPNE", "IF_ACMPEQ")
        );

        String expectedInverse = inversions.get(opcA);
        return expectedInverse != null && expectedInverse.equals(opcB);
    }

    private static boolean isLabelOnlyDifference(String origInsn, String recompInsn) {
        // Same opcode, different label: "IFEQ L4" vs "IFEQ L3"
        String origNoLabel = origInsn.replaceAll("\\bL\\d+\\b", "L?");
        String recompNoLabel = recompInsn.replaceAll("\\bL\\d+\\b", "L?");
        return origNoLabel.equals(recompNoLabel) && !origInsn.equals(recompInsn);
    }

    private static boolean containsStringConcat(String insn) {
        return insn.contains("makeConcatWithConstants") ||
               insn.contains("StringBuilder") ||
               insn.contains("String.valueOf");
    }

    private static boolean isNarrowingCast(String insn) {
        String opc = insn.split("\\s+")[0];
        return "I2B".equals(opc) || "I2S".equals(opc) || "I2C".equals(opc);
    }

    private static boolean isBoxingCall(String insn) {
        return insn.contains(".valueOf(") || insn.contains(".intValue()") ||
               insn.contains(".longValue()") || insn.contains(".floatValue()") ||
               insn.contains(".doubleValue()") || insn.contains(".booleanValue()");
    }

    private static boolean isConstantPropagation(String origInsn, String recompInsn) {
        boolean origIsField = origInsn.startsWith("GETSTATIC") || origInsn.startsWith("GETFIELD");
        boolean recompIsConst = isConstantPush(recompInsn);
        if (origIsField && recompIsConst) return true;

        boolean recompIsField = recompInsn.startsWith("GETSTATIC") || recompInsn.startsWith("GETFIELD");
        boolean origIsConst = isConstantPush(origInsn);
        return recompIsField && origIsConst;
    }

    private static boolean isConstantPush(String insn) {
        String opc = insn.split("\\s+")[0];
        return opc.startsWith("ICONST") || opc.startsWith("LCONST") ||
               opc.startsWith("FCONST") || opc.startsWith("DCONST") ||
               "BIPUSH".equals(opc) || "SIPUSH".equals(opc) ||
               "LDC".equals(opc) || "ACONST_NULL".equals(opc);
    }

    private static boolean isConstantEncodingDiff(String origInsn, String recompInsn) {
        return isConstantPush(origInsn) && isConstantPush(recompInsn) &&
               !origInsn.equals(recompInsn);
    }

    private static boolean isExpressionReorder(List<String> orig, List<String> recomp, int diffIdx) {
        // Check if a small window around the divergence contains the same instructions
        // in different order (commutative reordering)
        List<String> origWindow = extractWindow(orig, diffIdx, 4);
        List<String> recompWindow = extractWindow(recomp, diffIdx, 4);
        if (origWindow.isEmpty() || recompWindow.isEmpty()) return false;
        if (origWindow.size() != recompWindow.size()) return false;

        List<String> origSorted = new ArrayList<>(origWindow);
        List<String> recompSorted = new ArrayList<>(recompWindow);
        // Normalize labels before sorting
        origSorted.replaceAll(s -> s.replaceAll("\\bL\\d+\\b", "L?"));
        recompSorted.replaceAll(s -> s.replaceAll("\\bL\\d+\\b", "L?"));
        Collections.sort(origSorted);
        Collections.sort(recompSorted);
        return origSorted.equals(recompSorted) && !origWindow.equals(recompWindow);
    }

    private static List<String> extractWindow(List<String> context, int diffIdx, int windowSize) {
        List<String> result = new ArrayList<>();
        boolean found = false;
        for (String line : context) {
            String stripped = line.strip();
            if (!found && stripped.startsWith("#" + diffIdx + " ")) {
                found = true;
            }
            if (found) {
                result.add(extractInstruction(stripped));
                if (result.size() >= windowSize) break;
            }
        }
        return result;
    }

    private static boolean isInvokeDispatchDiff(String origInsn, String recompInsn) {
        boolean origIsInvoke = origInsn.startsWith("INVOKEVIRTUAL") ||
                origInsn.startsWith("INVOKEINTERFACE") ||
                origInsn.startsWith("INVOKESPECIAL");
        boolean recompIsInvoke = recompInsn.startsWith("INVOKEVIRTUAL") ||
                recompInsn.startsWith("INVOKEINTERFACE") ||
                recompInsn.startsWith("INVOKESPECIAL");
        return origIsInvoke && recompIsInvoke && !origInsn.equals(recompInsn);
    }
}
