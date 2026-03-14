package com.github.zomboiddecompiler.verify;

import com.github.zomboiddecompiler.verify.BytecodeComparator.MethodResult;
import com.github.zomboiddecompiler.verify.BytecodeComparator.Status;

import java.util.*;
import java.util.regex.Pattern;

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
        EXIT_BLOCK_REORDER("Exit block reorder",
                "GOTO vs return instruction at divergence, or exit blocks reached via different paths"),
        STATIC_INIT_REORDER("Static initializer reorder",
                "Static initializer (<clinit>) with field assignment order difference"),
        LAMBDA_BODY_SWAP("Lambda body swap",
                "Lambda method body completely different (compiler assigned different lambda index)"),
        CONSTANT_FOLDING_ERROR("Constant folding error",
                "FCONST_1/DCONST_1 vs LDC float/double literal in arithmetic context"),
        VARIABLE_COPY_PROPAGATION("Variable copy propagation",
                "Load/store instructions with different variable operands (copy propagation difference)"),
        BOOLEAN_EXPR_RESTRUCTURE("Boolean expression restructure",
                "IFEQ/IFNE vs ICONST_0/ICONST_1 or IXOR vs branching for boolean logic"),
        TRY_RESOURCE_RESTRUCTURE("Try-with-resources restructure",
                "Different try-with-resources or synchronized block cleanup structure"),
        STRUCTURAL_DIVERGENCE("Structural divergence",
                "Moderate structural difference not matching a more specific pattern"),
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

        // --- New categories (most specific to least specific) ---

        // Constant folding error: FCONST_1/DCONST_1 vs LDC float/double in arithmetic context
        if (isConstantFoldingError(orig, recomp)) {
            return Category.CONSTANT_FOLDING_ERROR;
        }

        // Lambda body swap: lambda method with completely different body
        if (isLambdaBodySwap(mr.name(), mr.firstDiffIndex(), origDiff, recompDiff)) {
            return Category.LAMBDA_BODY_SWAP;
        }

        // Static initializer reorder: <clinit> with similar instruction counts
        if (isStaticInitReorder(mr.name(), origCount, recompCount)) {
            return Category.STATIC_INIT_REORDER;
        }

        // Boolean expression restructure: IXOR vs branching, or boolean branch pattern asymmetry
        // Checked before exit block since IXOR is a more specific pattern
        if (isBooleanExprRestructure(orig, recomp, mr.firstDiffIndex())) {
            return Category.BOOLEAN_EXPR_RESTRUCTURE;
        }

        // Exit block reorder: GOTO vs return, or different exit paths
        if (isExitBlockReorder(origDiff, recompDiff, orig, recomp, mr.firstDiffIndex())) {
            return Category.EXIT_BLOCK_REORDER;
        }

        // Try-with-resources restructure: addSuppressed, close(), or MONITOREXIT near divergence
        if (isTryResourceRestructure(orig, recomp, mr.firstDiffIndex(), origDiff, recompDiff)) {
            return Category.TRY_RESOURCE_RESTRUCTURE;
        }

        // Variable copy propagation: load/store with different operands
        if (isVariableCopyPropagation(origDiff, recompDiff)) {
            return Category.VARIABLE_COPY_PROPAGATION;
        }

        // Structural divergence: fallback for moderate structural differences
        // (instruction count difference > 3 and no other specific category matched)
        if (Math.abs(origCount - recompCount) > 3) {
            return Category.STRUCTURAL_DIVERGENCE;
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

    // --- New category detection methods ---

    private static final Set<String> RETURN_INSNS = Set.of(
            "IRETURN", "ARETURN", "RETURN", "LRETURN", "FRETURN", "DRETURN");

    private static final Set<String> LOAD_STORE_OPCODES = Set.of(
            "ALOAD", "ASTORE", "ILOAD", "ISTORE", "FLOAD", "FSTORE",
            "DLOAD", "DSTORE", "LLOAD", "LSTORE");

    private static final Pattern LAMBDA_PATTERN = Pattern.compile("lambda\\$.*\\$\\d+");

    /**
     * Get the opcode (first token) from an instruction string.
     */
    private static String opcode(String insn) {
        if (insn == null || insn.isEmpty()) return "";
        int space = insn.indexOf(' ');
        return space >= 0 ? insn.substring(0, space) : insn;
    }

    /**
     * Parse context lines into (instructionIndex, instructionText) pairs.
     */
    private static List<Map.Entry<Integer, String>> parseContext(List<String> context) {
        List<Map.Entry<Integer, String>> result = new ArrayList<>();
        if (context == null) return result;
        for (String line : context) {
            String stripped = line.strip();
            if (!stripped.startsWith("#")) continue;
            int space = stripped.indexOf(' ');
            if (space < 0) continue;
            try {
                int idx = Integer.parseInt(stripped.substring(1, space).strip());
                String insn = stripped.substring(space).strip();
                result.add(Map.entry(idx, insn));
            } catch (NumberFormatException e) {
                // skip malformed lines
            }
        }
        return result;
    }

    /**
     * Check if context lines near a target index contain a given substring.
     */
    private static boolean hasTextNear(List<Map.Entry<Integer, String>> parsed, int targetIdx,
                                       int window, String text) {
        for (var entry : parsed) {
            if (Math.abs(entry.getKey() - targetIdx) <= window) {
                if (entry.getValue().contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isLoadStore(String insn) {
        return LOAD_STORE_OPCODES.contains(opcode(insn));
    }

    /**
     * Constant folding error: one side has FCONST_1/DCONST_1, the other has LDC with a
     * float/double literal, within a context that includes FADD/FSUB/DADD/DSUB.
     */
    private static boolean isConstantFoldingError(List<String> orig, List<String> recomp) {
        String origAll = String.join(" ", orig);
        String recompAll = String.join(" ", recomp);

        boolean hasArith = origAll.contains("FADD") || origAll.contains("FSUB") ||
                origAll.contains("DADD") || origAll.contains("DSUB") ||
                recompAll.contains("FADD") || recompAll.contains("FSUB") ||
                recompAll.contains("DADD") || recompAll.contains("DSUB");
        if (!hasArith) return false;

        List<Map.Entry<Integer, String>> origParsed = parseContext(orig);
        List<Map.Entry<Integer, String>> recompParsed = parseContext(recomp);

        for (var oe : origParsed) {
            String oOpc = opcode(oe.getValue());
            for (var re : recompParsed) {
                String rOpc = opcode(re.getValue());
                boolean match =
                        (("FCONST_1".equals(oOpc) || "DCONST_1".equals(oOpc)) && "LDC".equals(rOpc)) ||
                        (("FCONST_1".equals(rOpc) || "DCONST_1".equals(rOpc)) && "LDC".equals(oOpc));
                if (match && Math.abs(oe.getKey() - re.getKey()) <= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lambda body swap: method name matches lambda$...$N and first divergence is at index 0 or 1,
     * indicating completely different lambda body (compiler assigned different lambda indices).
     * Excludes cases where both sides are simple load/store (those are variable copy propagation).
     */
    private static boolean isLambdaBodySwap(String methodName, int firstDiffIndex,
                                            String origDiff, String recompDiff) {
        if (firstDiffIndex > 1) return false;
        if (!LAMBDA_PATTERN.matcher(methodName).matches()) return false;
        // Exclude if both sides are load/store (variable renaming, not body swap)
        if (isLoadStore(origDiff) && isLoadStore(recompDiff)) return false;
        return true;
    }

    /**
     * Static initializer reorder: method is <clinit> and instruction counts are similar
     * (within 50% ratio), indicating field assignment order differs.
     */
    private static boolean isStaticInitReorder(String methodName, int origCount, int recompCount) {
        if (!"<clinit>".equals(methodName)) return false;
        if (origCount <= 0 || recompCount <= 0) return false;
        double ratio = (double) Math.max(origCount, recompCount) / Math.min(origCount, recompCount);
        return ratio <= 1.5;
    }

    /**
     * Boolean expression restructure: one side uses IXOR for boolean negation while the other
     * uses branching (IFEQ/IFNE), or one side has the IFEQ/IFNE+ICONST_0+GOTO+ICONST_1 pattern
     * while the other side uses a direct value.
     */
    private static boolean isBooleanExprRestructure(List<String> orig, List<String> recomp,
                                                     int firstDiffIndex) {
        String origAll = String.join(" ", orig);
        String recompAll = String.join(" ", recomp);

        List<Map.Entry<Integer, String>> origParsed = parseContext(orig);
        List<Map.Entry<Integer, String>> recompParsed = parseContext(recomp);

        // IXOR pattern: one side uses IXOR, the other has IFEQ/IFNE
        if (origAll.contains("IXOR") || recompAll.contains("IXOR")) {
            Set<String> boolBranches = Set.of("IFEQ", "IFNE");
            boolean hasBoolBranch = false;
            for (var e : origParsed) {
                if (boolBranches.contains(opcode(e.getValue()))) { hasBoolBranch = true; break; }
            }
            if (!hasBoolBranch) {
                for (var e : recompParsed) {
                    if (boolBranches.contains(opcode(e.getValue()))) { hasBoolBranch = true; break; }
                }
            }
            if (hasBoolBranch) return true;
        }

        // Boolean branch pattern asymmetry: one side has IF+ICONST+GOTO+ICONST, other doesn't
        boolean origHasBoolPattern = hasBooleanBranchPattern(origParsed, firstDiffIndex, 5);
        boolean recompHasBoolPattern = hasBooleanBranchPattern(recompParsed, firstDiffIndex, 5);
        return origHasBoolPattern != recompHasBoolPattern;
    }

    /**
     * Check for the IFNE/IFEQ + ICONST_0/1 + GOTO + ICONST_0/1 boolean branch pattern
     * within a window around the target index.
     */
    private static boolean hasBooleanBranchPattern(List<Map.Entry<Integer, String>> parsed,
                                                    int targetIdx, int window) {
        Set<String> ifOps = Set.of("IFEQ", "IFNE");
        Set<String> constOps = Set.of("ICONST_0", "ICONST_1");

        for (int i = 0; i < parsed.size() - 3; i++) {
            int idx = parsed.get(i).getKey();
            if (Math.abs(idx - targetIdx) > window) continue;

            String opc0 = opcode(parsed.get(i).getValue());
            String opc1 = opcode(parsed.get(i + 1).getValue());
            String opc2 = opcode(parsed.get(i + 2).getValue());
            String opc3 = opcode(parsed.get(i + 3).getValue());

            if (ifOps.contains(opc0) && constOps.contains(opc1) &&
                "GOTO".equals(opc2) && constOps.contains(opc3)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exit block reorder: one side has GOTO and the other has a return instruction at
     * the divergence point, or both sides reach the same return type but via different
     * paths (one inlines the return, the other jumps to a shared exit block).
     */
    private static boolean isExitBlockReorder(String origDiff, String recompDiff,
                                              List<String> orig, List<String> recomp,
                                              int firstDiffIndex) {
        String origOpc = opcode(origDiff);
        String recompOpc = opcode(recompDiff);

        // Direct: GOTO vs return instruction
        if ("GOTO".equals(origOpc) && RETURN_INSNS.contains(recompOpc)) return true;
        if ("GOTO".equals(recompOpc) && RETURN_INSNS.contains(origOpc)) return true;

        // One side has a return at divergence, the other doesn't
        if (RETURN_INSNS.contains(origOpc) && !RETURN_INSNS.contains(recompOpc)) return true;
        if (RETURN_INSNS.contains(recompOpc) && !RETURN_INSNS.contains(origOpc)) return true;

        List<Map.Entry<Integer, String>> origParsed = parseContext(orig);
        List<Map.Entry<Integer, String>> recompParsed = parseContext(recomp);

        // GOTO at divergence and a return nearby on the other side (inlined exit)
        if ("GOTO".equals(origOpc) && hasOpcodeInRange(recompParsed, firstDiffIndex, firstDiffIndex + 3, RETURN_INSNS)) {
            return true;
        }
        if ("GOTO".equals(recompOpc) && hasOpcodeInRange(origParsed, firstDiffIndex, firstDiffIndex + 3, RETURN_INSNS)) {
            return true;
        }

        // GOTO at divergence on one side, other side has different block order
        if ("GOTO".equals(origOpc) && !"GOTO".equals(recompOpc)) return true;
        if ("GOTO".equals(recompOpc) && !"GOTO".equals(origOpc)) return true;

        // Return within 3 instructions after divergence on one side,
        // GOTO within 3 on the other (different exit paths for the same block)
        boolean origNearReturn = hasOpcodeAfter(origParsed, firstDiffIndex, 3, RETURN_INSNS);
        boolean recompNearReturn = hasOpcodeAfter(recompParsed, firstDiffIndex, 3, RETURN_INSNS);
        boolean origNearGoto = hasOpcodeAfter(origParsed, firstDiffIndex, 3, Set.of("GOTO"));
        boolean recompNearGoto = hasOpcodeAfter(recompParsed, firstDiffIndex, 3, Set.of("GOTO"));

        if (origNearReturn && recompNearGoto && !origNearGoto) return true;
        if (recompNearReturn && origNearGoto && !recompNearGoto) return true;

        // One side has GOTO nearby, other has return nearby (covers reordered exit blocks)
        if (origNearReturn && recompNearGoto) return true;
        if (recompNearReturn && origNearGoto) return true;

        return false;
    }

    /**
     * Check if any of the given opcodes appear in a specific index range [startIdx, endIdx].
     */
    private static boolean hasOpcodeInRange(List<Map.Entry<Integer, String>> parsed,
                                            int startIdx, int endIdx, Set<String> opcodes) {
        for (var entry : parsed) {
            int idx = entry.getKey();
            if (idx >= startIdx && idx <= endIdx) {
                if (opcodes.contains(opcode(entry.getValue()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if any of the given opcodes appear within N instructions after a start index.
     */
    private static boolean hasOpcodeAfter(List<Map.Entry<Integer, String>> parsed,
                                          int startIdx, int window, Set<String> opcodes) {
        for (var entry : parsed) {
            int idx = entry.getKey();
            if (idx > startIdx && idx <= startIdx + window) {
                if (opcodes.contains(opcode(entry.getValue()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Try-with-resources / synchronized block restructure: context lines near the divergence
     * contain addSuppressed, close(), or MONITOREXIT, indicating structural differences in
     * resource cleanup code.
     */
    private static boolean isTryResourceRestructure(List<String> orig, List<String> recomp,
                                                     int firstDiffIndex,
                                                     String origDiff, String recompDiff) {
        List<Map.Entry<Integer, String>> origParsed = parseContext(orig);
        List<Map.Entry<Integer, String>> recompParsed = parseContext(recomp);

        // addSuppressed near divergence point is a strong indicator
        if (hasTextNear(origParsed, firstDiffIndex, 5, "addSuppressed") ||
            hasTextNear(recompParsed, firstDiffIndex, 5, "addSuppressed")) {
            return true;
        }

        // MONITOREXIT near divergence with different opcodes at divergence
        if (!opcode(origDiff).equals(opcode(recompDiff))) {
            if (hasTextNear(origParsed, firstDiffIndex, 5, "MONITOREXIT") ||
                hasTextNear(recompParsed, firstDiffIndex, 5, "MONITOREXIT")) {
                return true;
            }
        }

        // close() with IFNULL guard pattern (auto-close from try-with-resources)
        boolean hasClose = hasTextNear(origParsed, firstDiffIndex, 5, ".close()") ||
                hasTextNear(recompParsed, firstDiffIndex, 5, ".close()");
        boolean hasIfNull = hasTextNear(origParsed, firstDiffIndex, 5, "IFNULL") ||
                hasTextNear(recompParsed, firstDiffIndex, 5, "IFNULL");
        if (hasClose && hasIfNull) {
            return true;
        }

        return false;
    }

    /**
     * Variable copy propagation: both divergent instructions are load/store types but with
     * different operands (variable renaming), or one side has an extra load+store pair
     * that the other side eliminated via copy propagation.
     */
    private static boolean isVariableCopyPropagation(String origDiff, String recompDiff) {
        // Both sides are load/store with different operands
        if (isLoadStore(origDiff) && isLoadStore(recompDiff)) return true;
        // One side has a load/store while the other has a non-control-flow instruction,
        // indicating the compiler inserted or eliminated a variable copy
        if (isLoadStore(origDiff) || isLoadStore(recompDiff)) {
            String otherOpc = isLoadStore(origDiff) ? opcode(recompDiff) : opcode(origDiff);
            // Don't match if the other side is control flow (handled by EXIT_BLOCK_REORDER)
            if (!RETURN_INSNS.contains(otherOpc) && !"GOTO".equals(otherOpc)) {
                return true;
            }
        }
        return false;
    }
}
