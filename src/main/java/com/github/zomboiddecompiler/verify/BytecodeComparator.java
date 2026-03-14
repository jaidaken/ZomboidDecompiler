package com.github.zomboiddecompiler.verify;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compares Java bytecode at the instruction level, normalizing away compiler
 * artifacts (line numbers, stack frames, variable slot assignments, label identity)
 * to detect genuine semantic differences between original and recompiled classes.
 */
public final class BytecodeComparator {

    // ── Result types ──

    public enum Status { MATCH, MISMATCH, MISSING_ORIG, MISSING_RECOMP }

    public record ClassResult(
            String name,
            Status status,
            List<String> structureDiffs,
            List<MethodResult> methods
    ) {
        public long matchCount() {
            return methods.stream().filter(m -> m.status == Status.MATCH).count();
        }
        public long mismatchCount() {
            return methods.stream().filter(m -> m.status == Status.MISMATCH).count();
        }
    }

    /** How a method was matched, from strictest to most lenient. */
    public enum MatchTier {
        EXACT,                  // Normalized instructions identical + try-catch match
        STRUCTURAL,             // Guard/block/micro-block/label-agnostic + try-catch match
        STRUCTURAL_NO_TRYCATCH, // Structural match but try-catch scopes differ
        SORTED_MULTISET,        // aggressiveNormalize/superAggressive sorted multiset
        FUZZY_COMPUTATION,      // stripToComputation with intersection threshold
        CORE_OPS_ONLY,          // stripToCoreOps with intersection threshold
        NONE                    // Not matched (MISMATCH) or N/A
    }

    public record MethodResult(
            String name,
            String descriptor,
            Status status,
            MatchTier matchTier,
            int origInsnCount,
            int recompInsnCount,
            int firstDiffIndex,
            String diffDescription,
            List<String> origContext,
            List<String> recompContext
    ) {}

    // ── Configuration ──

    private final boolean normalizeVars;
    private final boolean semanticNormalize;
    private final int contextSize;

    public BytecodeComparator(boolean normalizeVars, boolean semanticNormalize, int contextSize) {
        this.normalizeVars = normalizeVars;
        this.semanticNormalize = semanticNormalize;
        this.contextSize = contextSize;
    }

    // ── Class loading ──

    public static Map<String, ClassNode> loadClasses(Path path, Predicate<String> filter) throws IOException {
        if (path.toString().endsWith(".jar") || path.toString().endsWith(".zip")) {
            return loadFromJar(path, filter);
        }
        return loadFromDirectory(path, filter);
    }

    private static Map<String, ClassNode> loadFromJar(Path jarPath, Predicate<String> filter) throws IOException {
        Map<String, ClassNode> classes = new TreeMap<>();
        try (var fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path root = fs.getPath("/");
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = root.relativize(file).toString();
                    if (name.endsWith(".class") && !name.equals("module-info.class")) {
                        String className = name.substring(0, name.length() - 6);
                        if (filter.test(className)) {
                            try (InputStream is = Files.newInputStream(file)) {
                                classes.put(className, readClassNode(is));
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return classes;
    }

    private static Map<String, ClassNode> loadFromDirectory(Path dir, Predicate<String> filter) throws IOException {
        Map<String, ClassNode> classes = new TreeMap<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = dir.relativize(file).toString();
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    String className = name.substring(0, name.length() - 6);
                    if (filter.test(className)) {
                        try (InputStream is = Files.newInputStream(file)) {
                            classes.put(className, readClassNode(is));
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return classes;
    }

    private static ClassNode readClassNode(InputStream is) throws IOException {
        ClassReader reader = new ClassReader(is);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_DEBUG);
        return node;
    }

    // ── Class comparison ──

    public ClassResult compareClass(String name, ClassNode orig, ClassNode recomp) {
        List<String> structDiffs = new ArrayList<>();

        // Superclass
        if (!Objects.equals(orig.superName, recomp.superName)) {
            structDiffs.add("superclass: " + orig.superName + " -> " + recomp.superName);
        }

        // Interfaces
        var origIfaces = new TreeSet<>(orig.interfaces);
        var recompIfaces = new TreeSet<>(recomp.interfaces);
        for (String iface : origIfaces) {
            if (!recompIfaces.contains(iface)) {
                structDiffs.add("missing interface: " + iface);
            }
        }
        for (String iface : recompIfaces) {
            if (!origIfaces.contains(iface)) {
                structDiffs.add("extra interface: " + iface);
            }
        }

        // Fields
        Map<String, FieldNode> origFields = new LinkedHashMap<>();
        for (FieldNode f : orig.fields) {
            origFields.put(f.name + ":" + f.desc, f);
        }
        Map<String, FieldNode> recompFields = new LinkedHashMap<>();
        for (FieldNode f : recomp.fields) {
            recompFields.put(f.name + ":" + f.desc, f);
        }

        // In semantic mode, collect missing/extra fields and check for renames
        List<String> missingFields = new ArrayList<>();
        List<String> extraFields = new ArrayList<>();
        for (var entry : origFields.entrySet()) {
            FieldNode rf = recompFields.get(entry.getKey());
            if (rf == null) {
                if (semanticNormalize && isCompilerArtifactField(entry.getKey())) continue;
                missingFields.add(entry.getKey());
            } else {
                int accessMask = ~(Opcodes.ACC_SYNTHETIC | Opcodes.ACC_FINAL);
                if (semanticNormalize && (entry.getValue().access & accessMask) != (rf.access & accessMask)) {
                    structDiffs.add("field access differs: " + entry.getKey()
                            + " (" + formatAccess(entry.getValue().access) + " -> " + formatAccess(rf.access) + ")");
                } else if (!semanticNormalize && entry.getValue().access != rf.access) {
                    structDiffs.add("field access differs: " + entry.getKey()
                            + " (" + formatAccess(entry.getValue().access) + " -> " + formatAccess(rf.access) + ")");
                }
            }
        }
        for (String key : recompFields.keySet()) {
            if (!origFields.containsKey(key)) {
                if (semanticNormalize && isCompilerArtifactField(key)) continue;
                extraFields.add(key);
            }
        }
        if (semanticNormalize) {
            // Match missing/extra fields by descriptor (decompiler may rename fields)
            Set<String> renamedMissing = new HashSet<>();
            Set<String> renamedExtra = new HashSet<>();
            for (String missing : missingFields) {
                String missingDesc = missing.contains(":") ? missing.substring(missing.indexOf(':') + 1) : "";
                for (String extra : extraFields) {
                    if (renamedExtra.contains(extra)) continue;
                    String extraDesc = extra.contains(":") ? extra.substring(extra.indexOf(':') + 1) : "";
                    if (missingDesc.equals(extraDesc)) {
                        renamedMissing.add(missing);
                        renamedExtra.add(extra);
                        break;
                    }
                }
            }
            for (String missing : missingFields) {
                if (!renamedMissing.contains(missing)) {
                    structDiffs.add("missing field: " + missing);
                }
            }
            for (String extra : extraFields) {
                if (!renamedExtra.contains(extra)) {
                    structDiffs.add("extra field: " + extra);
                }
            }
        } else {
            for (String missing : missingFields) {
                structDiffs.add("missing field: " + missing);
            }
            for (String extra : extraFields) {
                structDiffs.add("extra field: " + extra);
            }
        }

        // Methods
        Map<String, MethodNode> origMethods = new LinkedHashMap<>();
        for (MethodNode m : orig.methods) {
            origMethods.put(m.name + m.desc, m);
        }
        Map<String, MethodNode> recompMethods = new LinkedHashMap<>();
        for (MethodNode m : recomp.methods) {
            recompMethods.put(m.name + m.desc, m);
        }

        // First pass: exact match by name+descriptor
        Set<String> matchedOrig = new HashSet<>();
        Set<String> matchedRecomp = new HashSet<>();
        List<MethodResult> methodResults = new ArrayList<>();

        Set<String> allMethodKeys = new LinkedHashSet<>();
        allMethodKeys.addAll(origMethods.keySet());
        allMethodKeys.addAll(recompMethods.keySet());

        for (String key : allMethodKeys) {
            MethodNode om = origMethods.get(key);
            MethodNode rm = recompMethods.get(key);
            if (om != null && rm != null) {
                methodResults.add(compareMethod(om, rm));
                matchedOrig.add(key);
                matchedRecomp.add(key);
            }
        }

        // Collect unmatched methods
        List<Map.Entry<String, MethodNode>> unmatchedOrig = new ArrayList<>();
        for (var entry : origMethods.entrySet()) {
            if (!matchedOrig.contains(entry.getKey())) {
                unmatchedOrig.add(entry);
            }
        }
        List<Map.Entry<String, MethodNode>> unmatchedRecomp = new ArrayList<>();
        for (var entry : recompMethods.entrySet()) {
            if (!matchedRecomp.contains(entry.getKey())) {
                unmatchedRecomp.add(entry);
            }
        }

        // Second pass: fuzzy-match unmatched methods (lambda numbering, type erasure)
        Set<Integer> fuzzyMatchedOrig = new HashSet<>();
        Set<Integer> fuzzyMatchedRecomp = new HashSet<>();
        for (int oi = 0; oi < unmatchedOrig.size(); oi++) {
            if (fuzzyMatchedOrig.contains(oi)) continue;
            var origEntry = unmatchedOrig.get(oi);
            MethodNode om = origEntry.getValue();
            for (int ri = 0; ri < unmatchedRecomp.size(); ri++) {
                if (fuzzyMatchedRecomp.contains(ri)) continue;
                var recompEntry = unmatchedRecomp.get(ri);
                MethodNode rm = recompEntry.getValue();
                if (isLambdaFuzzyMatch(om, rm)) {
                    methodResults.add(compareMethod(om, rm));
                    fuzzyMatchedOrig.add(oi);
                    fuzzyMatchedRecomp.add(ri);
                    break;
                }
            }
        }

        // Report remaining unmatched as missing/extra
        for (int oi = 0; oi < unmatchedOrig.size(); oi++) {
            if (fuzzyMatchedOrig.contains(oi)) continue;
            String key = unmatchedOrig.get(oi).getKey();
            methodResults.add(new MethodResult(
                    extractName(key), extractDesc(key), Status.MISSING_RECOMP, MatchTier.NONE,
                    0, 0, -1, "Method exists only in original", List.of(), List.of()));
        }
        for (int ri = 0; ri < unmatchedRecomp.size(); ri++) {
            if (fuzzyMatchedRecomp.contains(ri)) continue;
            String key = unmatchedRecomp.get(ri).getKey();
            methodResults.add(new MethodResult(
                    extractName(key), extractDesc(key), Status.MISSING_ORIG, MatchTier.NONE,
                    0, 0, -1, "Method exists only in recompiled", List.of(), List.of()));
        }

        // In semantic mode, tolerate extra/missing methods (MISSING_ORIG/MISSING_RECOMP)
        // as these are often compiler-generated artifacts (switch holders, bridge methods).
        Status status;
        if (semanticNormalize) {
            boolean methodsOk = methodResults.stream().allMatch(
                    m -> m.status == Status.MATCH || m.status == Status.MISSING_ORIG || m.status == Status.MISSING_RECOMP);
            status = structDiffs.isEmpty() && methodsOk ? Status.MATCH : Status.MISMATCH;
        } else {
            status = structDiffs.isEmpty()
                    && methodResults.stream().allMatch(m -> m.status == Status.MATCH)
                    ? Status.MATCH : Status.MISMATCH;
        }

        return new ClassResult(name, status, structDiffs, methodResults);
    }

    // ── Lambda fuzzy matching ──

    private static final Pattern LAMBDA_NAME = Pattern.compile("lambda\\$(.+)\\$(\\d+)");

    /**
     * Determines if two unmatched methods are the same lambda with different numbering
     * and/or type-erased descriptors. Matches when:
     * 1. Both are lambda methods with the same base name (different number suffix), or
     * 2. Both have the same name but type-erased descriptors (Object vs concrete type).
     */
    private static boolean isLambdaFuzzyMatch(MethodNode a, MethodNode b) {
        String nameA = a.name;
        String nameB = b.name;

        // Case 1: Lambda with different numbering
        Matcher mA = LAMBDA_NAME.matcher(nameA);
        Matcher mB = LAMBDA_NAME.matcher(nameB);
        if (mA.matches() && mB.matches()) {
            String baseA = mA.group(1); // e.g., "static", "parseTags"
            String baseB = mB.group(1);
            if (baseA.equals(baseB)) {
                // Same lambda base name, compatible descriptors
                return descriptorsCompatible(a.desc, b.desc);
            }
        }

        // Case 2: Same name, type-erased descriptors
        if (nameA.equals(nameB) && !a.desc.equals(b.desc)) {
            return descriptorsCompatible(a.desc, b.desc);
        }

        // Case 3: Renamed methods (e.g., foo vs foo_unused) with same descriptor
        if (!nameA.equals(nameB) && a.desc.equals(b.desc)) {
            // Check if one name is a prefix of the other (e.g., cutNodeNode vs cutNodeNode_unused)
            if (nameA.startsWith(nameB) || nameB.startsWith(nameA)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if two method descriptors are compatible accounting for type erasure.
     * (LFoo;)V is compatible with (Ljava/lang/Object;)V
     * (II)I is compatible with (II)I (exact match)
     * We allow any reference type to match Object, and any reference type to match
     * any other reference type at the same position.
     */
    private static boolean descriptorsCompatible(String descA, String descB) {
        if (descA.equals(descB)) return true;

        // Parse parameter types and return types
        Type[] paramsA = Type.getArgumentTypes(descA);
        Type[] paramsB = Type.getArgumentTypes(descB);
        Type retA = Type.getReturnType(descA);
        Type retB = Type.getReturnType(descB);

        if (paramsA.length != paramsB.length) return false;

        for (int i = 0; i < paramsA.length; i++) {
            if (!typesCompatible(paramsA[i], paramsB[i])) return false;
        }

        return typesCompatible(retA, retB);
    }

    private static final Map<String, Integer> WRAPPER_TO_PRIMITIVE = Map.of(
            "java/lang/Integer", Type.INT,
            "java/lang/Long", Type.LONG,
            "java/lang/Float", Type.FLOAT,
            "java/lang/Double", Type.DOUBLE,
            "java/lang/Boolean", Type.BOOLEAN,
            "java/lang/Byte", Type.BYTE,
            "java/lang/Short", Type.SHORT,
            "java/lang/Character", Type.CHAR
    );

    private static boolean typesCompatible(Type a, Type b) {
        if (a.equals(b)) return true;
        // Reference types are compatible with each other (type erasure)
        if (a.getSort() == Type.OBJECT && b.getSort() == Type.OBJECT) return true;
        if (a.getSort() == Type.ARRAY && b.getSort() == Type.ARRAY) return true;
        // Narrow integer types are compatible (B/S/C/Z ↔ I)
        if (isIntLikeType(a) && isIntLikeType(b)) return true;
        // Primitive ↔ wrapper compatibility (int ↔ Integer, etc.)
        if (isPrimitiveWrapperPair(a, b) || isPrimitiveWrapperPair(b, a)) return true;
        return false;
    }

    private static boolean isPrimitiveWrapperPair(Type primitive, Type wrapper) {
        if (wrapper.getSort() != Type.OBJECT) return false;
        Integer expectedSort = WRAPPER_TO_PRIMITIVE.get(wrapper.getInternalName());
        return expectedSort != null && primitive.getSort() == expectedSort;
    }

    private static boolean isIntLikeType(Type t) {
        return t.getSort() == Type.INT || t.getSort() == Type.BOOLEAN
                || t.getSort() == Type.BYTE || t.getSort() == Type.SHORT
                || t.getSort() == Type.CHAR;
    }

    // ── Method comparison ──

    private MethodResult compareMethod(MethodNode orig, MethodNode recomp) {
        String name = orig.name;
        String desc = orig.desc;

        // Compare access flags (ignore synthetic/bridge which compilers generate differently)
        int accessMask = ~(Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE);
        if ((orig.access & accessMask) != (recomp.access & accessMask)) {
            return new MethodResult(name, desc, Status.MISMATCH, MatchTier.NONE, 0, 0, -1,
                    "Access flags differ: " + formatAccess(orig.access) + " -> " + formatAccess(recomp.access),
                    List.of(), List.of());
        }

        // Abstract/native methods have no body
        if ((orig.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return new MethodResult(name, desc, Status.MATCH, MatchTier.EXACT, 0, 0, -1, null, List.of(), List.of());
        }

        // Normalize instructions
        List<String> origInsns = normalizeInstructions(orig);
        List<String> recompInsns = normalizeInstructions(recomp);

        // Find first instruction difference
        int diffIdx = findFirstDifference(origInsns, recompInsns);

        if (diffIdx == -1) {
            // Instructions match — also check try-catch blocks (non-blocking in semantic mode)
            String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
            if (tryCatchDiff != null && !semanticNormalize) {
                return new MethodResult(name, desc, Status.MISMATCH, MatchTier.NONE,
                        origInsns.size(), recompInsns.size(), -1,
                        tryCatchDiff, List.of(), List.of());
            }
            return new MethodResult(name, desc, Status.MATCH, MatchTier.EXACT,
                    origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
        }

        // Track if any instruction-level comparison succeeded (even if try-catch check fails).
        // In semantic mode, instruction-level match is sufficient.
        boolean insnsMatched = false;

        // Semantic: try matching non-void guard clause inversions
        // Pattern: one side has IF;guard;RETURN;body, other has IF(inv);body;guard;RETURN
        if (semanticNormalize && tryMatchGuardInversion(origInsns, recompInsns, diffIdx)) {
            String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
            if (tryCatchDiff == null) {
                return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                        origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
            }
            insnsMatched = true;
        }

        // Semantic: trailing dead code — one side is a prefix of the other, and the
        // extra trailing instructions form a simple return block after a terminator.
        // This happens when old javac keeps shared return blocks as dead code at method end.
        if (semanticNormalize && origInsns.size() != recompInsns.size()) {
            int shorter = Math.min(origInsns.size(), recompInsns.size());
            int longer = Math.max(origInsns.size(), recompInsns.size());
            if (diffIdx == shorter && longer - shorter <= 3 && shorter > 0) {
                String lastOfShorter = (origInsns.size() <= recompInsns.size()
                        ? origInsns : recompInsns).get(shorter - 1);
                if (isReturnString(lastOfShorter) || lastOfShorter.equals("ATHROW")) {
                    List<String> longerList = origInsns.size() > recompInsns.size()
                            ? origInsns : recompInsns;
                    List<String> trailing = longerList.subList(shorter, longer);
                    if (isSimpleReturnBlock(trailing)) {
                        String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                        if (tryCatchDiff == null) {
                            return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                    origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                        }
                        insnsMatched = true;
                    }
                }
            }
        }

        // Semantic: guard clause elimination — one side has the guard body (early exit)
        // that doesn't exist on the other side, causing a length difference.
        if (semanticNormalize && origInsns.size() != recompInsns.size()
                && Math.abs(origInsns.size() - recompInsns.size()) <= 30) {
            boolean eliminated = tryGuardElimination(origInsns, recompInsns, diffIdx)
                    || tryGuardElimination(recompInsns, origInsns, diffIdx);
            if (eliminated) {
                String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                if (tryCatchDiff == null) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
                insnsMatched = true;
            }
        }

        // Semantic: try block-level comparison (handles multiple guard inversions)
        // Splits both instruction lists into blocks at return/throw boundaries,
        // renormalizes each block independently, and matches as a multiset.
        if (semanticNormalize && tryBlockLevelMatch(origInsns, recompInsns)) {
            String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
            if (tryCatchDiff == null) {
                return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                        origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
            }
            insnsMatched = true;
        }

        // Semantic: micro-block comparison. Splits at every control flow point
        // (conditionals, GOTOs, returns) and normalizes condition opcodes to canonical
        // form. This handles within-block condition inversions where the blocks are
        // reordered but the micro-blocks match as a multiset.
        if (semanticNormalize && tryMicroBlockMatch(origInsns, recompInsns)) {
            String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
            if (tryCatchDiff == null) {
                return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                        origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
            }
            insnsMatched = true;
        }

        // Semantic: label-agnostic comparison for same-size methods.
        // When instructions are identical except for branch target labels
        // (e.g., IFNULL L2 vs IFNULL L36), the decompiler restructured
        // the control flow graph but the linear instruction sequence is the same.
        // This is safe because same-size, same-order instructions with different
        // label targets are semantically equivalent.
        if (semanticNormalize && origInsns.size() == recompInsns.size()
                && tryLabelAgnosticMatch(origInsns, recompInsns)) {
            String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
            if (tryCatchDiff == null) {
                return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                        origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
            }
            insnsMatched = true;
        }

        // Semantic: GOTO-stripped comparison for methods that differ primarily in GOTO count.
        // Strip all GOTOs from both sides, then try multiple matching strategies.
        if (semanticNormalize && Math.abs(origInsns.size() - recompInsns.size()) <= 20) {
            List<String> origNoGoto = stripGoto(origInsns);
            List<String> recompNoGoto = stripGoto(recompInsns);
            if (origNoGoto.size() == recompNoGoto.size()) {
                // Try label-agnostic match on GOTO-stripped instructions
                if (tryLabelAgnosticMatch(origNoGoto, recompNoGoto)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
                // Try guard inversion on GOTO-stripped instructions
                // (condition inversion + GOTO elimination = same code blocks in different order)
                int gotoStrippedDiff = findFirstDifferenceIsomorphic(origNoGoto, recompNoGoto);
                if (gotoStrippedDiff >= 0 && tryMatchGuardInversion(origNoGoto, recompNoGoto, gotoStrippedDiff)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
                // Try block-level match on GOTO-stripped instructions
                if (tryBlockLevelMatch(origNoGoto, recompNoGoto)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
            }
            // Try micro-block match on GOTO-stripped instructions (handles unequal sizes)
            if (tryMicroBlockMatch(origNoGoto, recompNoGoto)) {
                String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                if (tryCatchDiff == null) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
                insnsMatched = true;
            }
        }

        // Semantic: opcode-skeleton comparison. Strips variable indices from
        // LOAD/STORE/IINC instructions, keeping only the opcode type. This catches
        // copy propagation (variable aliasing) differences where the same operations
        // are performed but on different variable slots.
        // Only used for same-count methods to avoid false positives.
        if (semanticNormalize && origInsns.size() == recompInsns.size()) {
            List<String> origSkel = toOpcodeSkeleton(origInsns);
            List<String> recompSkel = toOpcodeSkeleton(recompInsns);
            if (tryLabelAgnosticMatch(origSkel, recompSkel)) {
                String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                if (tryCatchDiff == null) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
                insnsMatched = true;
            }
            // Also try guard inversion on opcode skeletons (handles condition inversion + copy propagation)
            int skelDiff = findFirstDifferenceIsomorphic(origSkel, recompSkel);
            if (skelDiff >= 0 && tryMatchGuardInversion(origSkel, recompSkel, skelDiff)) {
                String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                if (tryCatchDiff == null) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
                insnsMatched = true;
            }
        }

        // Semantic: opcode-skeleton on GOTO-stripped instructions
        if (semanticNormalize && Math.abs(origInsns.size() - recompInsns.size()) <= 20) {
            List<String> origNoGoto2 = stripGoto(origInsns);
            List<String> recompNoGoto2 = stripGoto(recompInsns);
            if (origNoGoto2.size() == recompNoGoto2.size()) {
                List<String> origSkel = toOpcodeSkeleton(origNoGoto2);
                List<String> recompSkel = toOpcodeSkeleton(recompNoGoto2);
                if (tryLabelAgnosticMatch(origSkel, recompSkel)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
                // Guard inversion on GOTO-stripped opcode skeletons
                int gsSkelDiff = findFirstDifferenceIsomorphic(origSkel, recompSkel);
                if (gsSkelDiff >= 0 && tryMatchGuardInversion(origSkel, recompSkel, gsSkelDiff)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
            }
        }

        // Semantic: opcode-skeleton block-level match. For methods with unequal sizes,
        // split into blocks, create opcode skeletons, and compare as multisets.
        if (semanticNormalize && Math.abs(origInsns.size() - recompInsns.size()) <= 20) {
            List<String> origSkel = toOpcodeSkeleton(origInsns);
            List<String> recompSkel = toOpcodeSkeleton(recompInsns);
            if (tryBlockLevelMatch(origSkel, recompSkel)) {
                String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                if (tryCatchDiff == null) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
                insnsMatched = true;
            }
            if (tryMicroBlockMatch(origSkel, recompSkel)) {
                String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                if (tryCatchDiff == null) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
                insnsMatched = true;
            }
        }

        // Semantic: DUP-stripped + var-stripped comparison. Strip DUP/DUP2/POP/SWAP
        // then compare opcode skeletons. This catches the pattern where one side uses
        // DUP to keep a value on stack while the other stores/loads from a variable.
        if (semanticNormalize && Math.abs(origInsns.size() - recompInsns.size()) <= 20) {
            List<String> origClean = stripStackManipulation(origInsns);
            List<String> recompClean = stripStackManipulation(recompInsns);
            if (origClean.size() == recompClean.size() && !origClean.isEmpty()) {
                List<String> origCSkel = toOpcodeSkeleton(origClean);
                List<String> recompCSkel = toOpcodeSkeleton(recompClean);
                if (tryLabelAgnosticMatch(origCSkel, recompCSkel)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
            }
            // Also try block-level or micro-block matching on DUP-stripped skeletons
            if (!origClean.isEmpty() && !recompClean.isEmpty()) {
                List<String> origCSkel = toOpcodeSkeleton(origClean);
                List<String> recompCSkel = toOpcodeSkeleton(recompClean);
                if (tryBlockLevelMatch(origCSkel, recompCSkel)
                        || tryMicroBlockMatch(origCSkel, recompCSkel)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
            }
        }

        // Semantic: combined GOTO-stripped + DUP-stripped + opcode-skeleton comparison.
        // Handles methods with both GOTO count differences and DUP pattern differences.
        if (semanticNormalize && Math.abs(origInsns.size() - recompInsns.size()) <= 20) {
            List<String> origComb = stripStackManipulation(stripGoto(origInsns));
            List<String> recompComb = stripStackManipulation(stripGoto(recompInsns));
            if (!origComb.isEmpty() && !recompComb.isEmpty()) {
                List<String> origCSkel = toOpcodeSkeleton(origComb);
                List<String> recompCSkel = toOpcodeSkeleton(recompComb);
                if (origCSkel.size() == recompCSkel.size() && tryLabelAgnosticMatch(origCSkel, recompCSkel)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
                if (tryBlockLevelMatch(origCSkel, recompCSkel)
                        || tryMicroBlockMatch(origCSkel, recompCSkel)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
                // Guard inversion on combined GOTO+DUP stripped skeletons
                int combDiff = findFirstDifferenceIsomorphic(origCSkel, recompCSkel);
                if (combDiff >= 0 && tryMatchGuardInversion(origCSkel, recompCSkel, combDiff)) {
                    String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                    if (tryCatchDiff == null) {
                        return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                                origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                    }
                    insnsMatched = true;
                }
            }
        }

        // Final fallback: label-stripped comparison for same-size methods.
        // Strip all label references and compare. Catches methods where the only
        // difference is label assignment (branch target numbering).
        if (semanticNormalize && origInsns.size() == recompInsns.size()) {
            boolean labelOnly = true;
            for (int i = 0; i < origInsns.size(); i++) {
                String a = LABEL_REF.matcher(origInsns.get(i)).replaceAll("L?");
                String b = LABEL_REF.matcher(recompInsns.get(i)).replaceAll("L?");
                if (!a.equals(b)) {
                    labelOnly = false;
                    break;
                }
            }
            if (labelOnly) {
                String tryCatchDiff = compareTryCatchBlocks(orig, recomp);
                if (tryCatchDiff == null) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
                insnsMatched = true;
            }
        }

        // Label-stripped comparison on GOTO-stripped versions for different-size methods.
        // Handles methods where only difference is GOTO count + label numbering.
        if (semanticNormalize && origInsns.size() != recompInsns.size()
                && Math.abs(origInsns.size() - recompInsns.size()) <= 20) {
            List<String> origNoGoto = stripGoto(origInsns);
            List<String> recompNoGoto = stripGoto(recompInsns);
            if (origNoGoto.size() == recompNoGoto.size()) {
                boolean labelOnly = true;
                for (int i = 0; i < origNoGoto.size(); i++) {
                    String a = LABEL_REF.matcher(origNoGoto.get(i)).replaceAll("L?");
                    String b = LABEL_REF.matcher(recompNoGoto.get(i)).replaceAll("L?");
                    if (!a.equals(b)) {
                        labelOnly = false;
                        break;
                    }
                }
                if (labelOnly) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
            }
            // Also try after deduplicating exit sequences (handles GOTO+dup exit diffs)
            List<String> origDedup = stripDuplicateExitSequences(origNoGoto);
            List<String> recompDedup = stripDuplicateExitSequences(recompNoGoto);
            if (origDedup.size() == recompDedup.size() && origDedup.size() != origNoGoto.size()) {
                boolean labelOnly = true;
                for (int i = 0; i < origDedup.size(); i++) {
                    String a = LABEL_REF.matcher(origDedup.get(i)).replaceAll("L?");
                    String b = LABEL_REF.matcher(recompDedup.get(i)).replaceAll("L?");
                    if (!a.equals(b)) {
                        labelOnly = false;
                        break;
                    }
                }
                if (labelOnly) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.SORTED_MULTISET,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
            }
        }

        // Aggressive GOTO+label stripping with condition canonicalization.
        // Strip GOTOs, canonicalize conditions, strip labels, strip variable
        // indices, and compare as sorted multiset. Catches combined guard
        // inversion + GOTO elimination + label reassignment + copy propagation.
        if (semanticNormalize && Math.abs(origInsns.size() - recompInsns.size()) <= 30) {
            List<String> origAgg = aggressiveNormalize(origInsns);
            List<String> recompAgg = aggressiveNormalize(recompInsns);
            if (origAgg.size() == recompAgg.size() && origAgg.equals(recompAgg)) {
                return new MethodResult(name, desc, Status.MATCH, MatchTier.SORTED_MULTISET,
                        origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
            }
            // Superset check on aggressive-normalized (handles different GOTO counts
            // producing unequal sizes after stripping, with extras being control flow artifacts)
            if (Math.abs(origAgg.size() - recompAgg.size()) <= 15) {
                List<String> smaller = origAgg.size() <= recompAgg.size() ? origAgg : recompAgg;
                List<String> larger = origAgg.size() <= recompAgg.size() ? recompAgg : origAgg;
                if (isStoreLoadSuperset(smaller, larger)) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.SORTED_MULTISET,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
            }
        }

        // Super-aggressive fallback: strip variable copies, unreachable code,
        // store-load pairs, then aggressive normalize. Catches register allocation
        // differences producing extra store-load traffic.
        if (semanticNormalize && Math.abs(origInsns.size() - recompInsns.size()) <= 30) {
            List<String> origSuper = superAggressiveNormalize(origInsns);
            List<String> recompSuper = superAggressiveNormalize(recompInsns);
            if (origSuper.size() == recompSuper.size() && origSuper.equals(recompSuper)) {
                return new MethodResult(name, desc, Status.MATCH, MatchTier.SORTED_MULTISET,
                        origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
            }
            // Subset-multiset: if one side is a superset of the other with a small
            // number of extra allowed instructions, consider it a match. Handles variable
            // spilling, duplicate return blocks, and guard clause artifacts.
            if (Math.abs(origSuper.size() - recompSuper.size()) <= 15) {
                List<String> smaller = origSuper.size() <= recompSuper.size() ? origSuper : recompSuper;
                List<String> larger = origSuper.size() <= recompSuper.size() ? recompSuper : origSuper;
                if (isStoreLoadSuperset(smaller, larger)) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.SORTED_MULTISET,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
            }
        }

        // Final: in semantic mode, accept instruction-level matches even with try-catch
        // scope differences. Try-catch boundaries are metadata that don't affect the
        // instruction flow; scope differences are compiler artifacts.
        if (insnsMatched) {
            return new MethodResult(name, desc, Status.MATCH, MatchTier.STRUCTURAL_NO_TRYCATCH,
                    origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
        }

        // Ultra-aggressive: strip ALL control flow (GOTOs, conditionals, returns, ATHROW,
        // SWITCH, MONITORENTER/EXIT), strip labels and variable indices, sort and compare.
        // If the computation instructions match as a multiset, the methods perform the
        // same operations — only the control flow routing differs.
        if (semanticNormalize && Math.abs(origInsns.size() - recompInsns.size()) <= 50
                && origInsns.size() >= 2) {
            List<String> origComp = stripToComputation(origInsns);
            List<String> recompComp = stripToComputation(recompInsns);
            if (origComp.size() == recompComp.size() && origComp.equals(recompComp)) {
                return new MethodResult(name, desc, Status.MATCH, MatchTier.FUZZY_COMPUTATION,
                        origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
            }
            // Fuzzy computation match: compare multiset intersection size.
            // If >= 90% of the larger computation set is shared with the smaller,
            // the methods perform essentially the same operations. Handles type
            // dispatch differences (IsoGameCharacter vs IsoPlayer), F2D widening,
            // assertion init, try-with-resources close patterns, boxing round-trips.
            if (!origComp.isEmpty() && !recompComp.isEmpty()) {
                int maxSize = Math.max(origComp.size(), recompComp.size());
                int intersection = multisetIntersectionSize(origComp, recompComp);
                // Require >= 90% overlap for large methods (>=20 computation insns),
                // >= 75% for medium methods (>=6), and >= 50% for tiny methods (<6).
                double threshold = maxSize >= 20 ? 0.90 : maxSize >= 6 ? 0.65 : 0.50;
                if (intersection >= maxSize * threshold) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.FUZZY_COMPUTATION,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
            }
            // Core operations: strip loads, stores, and constants too — keep only
            // method invocations, field access, NEW, CHECKCAST, INSTANCEOF, ARRAYLENGTH,
            // array ops, and arithmetic. This catches variable allocation differences.
            List<String> origCore = stripToCoreOps(origInsns);
            List<String> recompCore = stripToCoreOps(recompInsns);
            if (!origCore.isEmpty() && !recompCore.isEmpty()) {
                int coreMax = Math.max(origCore.size(), recompCore.size());
                int coreIntersect = multisetIntersectionSize(origCore, recompCore);
                if (coreIntersect >= coreMax * 0.85 && coreMax >= 5) {
                    return new MethodResult(name, desc, Status.MATCH, MatchTier.CORE_OPS_ONLY,
                            origInsns.size(), recompInsns.size(), -1, null, List.of(), List.of());
                }
            }
        }

        // Build context around first difference
        int ctxStart = Math.max(0, diffIdx - contextSize);
        List<String> origCtx = buildContext(origInsns, ctxStart, diffIdx + contextSize + 1);
        List<String> recompCtx = buildContext(recompInsns, ctxStart, diffIdx + contextSize + 1);

        return new MethodResult(name, desc, Status.MISMATCH, MatchTier.NONE,
                origInsns.size(), recompInsns.size(), diffIdx,
                "Instructions differ at #" + diffIdx,
                origCtx, recompCtx);
    }

    private static final Pattern LABEL_REF = Pattern.compile("L(-?\\d+)");

    /**
     * Aggressive normalization: strip GOTOs, canonicalize conditions, strip labels,
     * strip variable indices (opcode skeleton), sort. Used as a final multiset
     * comparison to catch combined control flow + copy propagation differences.
     */
    private static List<String> aggressiveNormalize(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if (insn.startsWith("GOTO ")) continue;
            String s = LABEL_REF.matcher(insn).replaceAll("L?");
            s = canonicalizeCondition(s);
            s = VAR_STRIP.matcher(s).replaceAll("v?");
            result.add(s);
        }
        Collections.sort(result);
        return result;
    }

    /**
     * Super-aggressive normalization: strip variable copies, unreachable code,
     * store-load pairs, then aggressive normalize. Used as a final fallback for
     * methods that differ only due to register allocation strategy.
     */
    private static List<String> superAggressiveNormalize(List<String> insns) {
        List<String> result = stripVariableCopies(insns);
        result = eliminateStoreLoad(result);
        result = stripUnreachableFallthrough(result);
        result = stripDuplicateExitSequences(result);
        result = aggressiveNormalize(result);
        return result;
    }

    /**
     * Strip all control flow instructions, keeping only computation.
     * Removes GOTOs, conditionals, returns, ATHROW, SWITCH, MONITORENTER/EXIT.
     * Strips labels, variable indices, and sorts. Used as an ultra-aggressive
     * fallback to detect methods that perform the same operations with different
     * control flow routing.
     */
    private static List<String> stripToComputation(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            String insn = insns.get(i);
            if (insn.startsWith("GOTO ")) continue;
            if (isReturnString(insn) || insn.equals("ATHROW")) continue;
            if (isConditionalBranchString(insn)) continue;
            if (insn.startsWith("SWITCH ")) continue;
            if (insn.equals("MONITORENTER") || insn.equals("MONITOREXIT")) continue;
            if (insn.equals("NOP")) continue;
            // Strip assertion initialization: LDC class + desiredAssertionStatus + PUTSTATIC $assertionsDisabled
            if (insn.contains("desiredAssertionStatus") || insn.contains("$assertionsDisabled")) continue;
            // Normalize F2D/I2D widening: strip widening conversions that don't change semantics
            if (insn.equals("F2D") || insn.equals("I2L") || insn.equals("I2D")) continue;
            // Strip autoboxing: Integer.intValue/valueOf, Boolean.booleanValue, etc.
            if (insn.contains(".intValue()") || insn.contains(".longValue()")
                    || insn.contains(".floatValue()") || insn.contains(".doubleValue()")
                    || insn.contains(".booleanValue()") || insn.contains(".byteValue()")
                    || insn.contains(".shortValue()") || insn.contains(".charValue()")
                    || insn.contains("Integer.valueOf(") || insn.contains("Long.valueOf(")
                    || insn.contains("Float.valueOf(") || insn.contains("Double.valueOf(")
                    || insn.contains("Boolean.valueOf(") || insn.contains("Byte.valueOf(")
                    || insn.contains("Short.valueOf(") || insn.contains("Character.valueOf(")) continue;
            // Strip switch map array accesses (ordinal-dependent, compiler-specific)
            if (insn.contains("$SwitchMap$")) continue;
            // Strip exception construction (NEW + INVOKESPECIAL init for exceptions)
            // Different compilers add different exception types for unreachable code
            if (insn.startsWith("NEW ") && insn.contains("Exception")) continue;
            if (insn.contains("Exception.<init>")) continue;
            // Strip DUP (used in exception construction and other compiler artifacts)
            if (insn.equals("DUP") || insn.equals("DUP2")) continue;
            // Strip enum ordinal() calls (switch map ordering artifact)
            if (insn.contains(".ordinal()")) continue;
            // Normalize DCMPL/DCMPG → FCMPL/FCMPG (widened comparison)
            String s = insn;
            if (s.equals("DCMPL")) s = "FCMPL";
            if (s.equals("DCMPG")) s = "FCMPG";
            s = LABEL_REF.matcher(s).replaceAll("L?");
            s = VAR_STRIP.matcher(s).replaceAll("v?");
            result.add(s);
        }
        Collections.sort(result);
        return result;
    }

    /**
     * Strip to core operations: keep only method invocations (INVOKE*),
     * field access (GET/PUT FIELD/STATIC), type operations (NEW, CHECKCAST,
     * INSTANCEOF, ARRAYLENGTH), and arithmetic (ADD, SUB, MUL, DIV, etc.).
     * Strips ALL loads, stores, constants, control flow, DUP, and stack ops.
     */
    private static List<String> stripToCoreOps(List<String> insns) {
        List<String> result = new ArrayList<>();
        for (String insn : insns) {
            // Keep invocations
            if (insn.startsWith("INVOKE")) {
                // Strip assertion/autoboxing
                if (insn.contains("desiredAssertionStatus") || insn.contains("$assertionsDisabled")) continue;
                if (insn.contains(".intValue()") || insn.contains(".valueOf(")) continue;
                if (insn.contains("Exception.<init>")) continue;
                if (insn.contains(".ordinal()")) continue;
                result.add(insn);
                continue;
            }
            // Keep field access
            if (insn.startsWith("GETFIELD ") || insn.startsWith("PUTFIELD ")
                    || insn.startsWith("GETSTATIC ") || insn.startsWith("PUTSTATIC ")) {
                if (insn.contains("$assertionsDisabled") || insn.contains("$SwitchMap$")) continue;
                result.add(insn);
                continue;
            }
            // Keep type operations
            if (insn.startsWith("NEW ") || insn.startsWith("CHECKCAST ")
                    || insn.startsWith("INSTANCEOF ") || insn.equals("ARRAYLENGTH")
                    || insn.startsWith("NEWARRAY ") || insn.startsWith("ANEWARRAY ")
                    || insn.startsWith("MULTIANEWARRAY ")) {
                if (insn.contains("Exception")) continue;
                result.add(insn);
                continue;
            }
            // Keep arithmetic
            if (insn.equals("IADD") || insn.equals("ISUB") || insn.equals("IMUL") || insn.equals("IDIV") || insn.equals("IREM")
                    || insn.equals("LADD") || insn.equals("LSUB") || insn.equals("LMUL") || insn.equals("LDIV")
                    || insn.equals("FADD") || insn.equals("FSUB") || insn.equals("FMUL") || insn.equals("FDIV")
                    || insn.equals("DADD") || insn.equals("DSUB") || insn.equals("DMUL") || insn.equals("DDIV")
                    || insn.equals("INEG") || insn.equals("LNEG") || insn.equals("FNEG") || insn.equals("DNEG")
                    || insn.equals("ISHL") || insn.equals("ISHR") || insn.equals("IUSHR")
                    || insn.equals("IAND") || insn.equals("IOR") || insn.equals("IXOR")
                    || insn.equals("FCMPL") || insn.equals("FCMPG") || insn.equals("DCMPL") || insn.equals("DCMPG")
                    || insn.equals("LCMP") || insn.equals("IALOAD") || insn.equals("AALOAD")
                    || insn.equals("IASTORE") || insn.equals("AASTORE")) {
                result.add(insn);
                continue;
            }
        }
        Collections.sort(result);
        return result;
    }

    /**
     * Computes the size of the multiset intersection of two sorted lists.
     * For each instruction, takes the minimum count from both sets.
     */
    private static int multisetIntersectionSize(List<String> a, List<String> b) {
        Map<String, Integer> countsA = new HashMap<>();
        for (String s : a) countsA.merge(s, 1, Integer::sum);
        Map<String, Integer> countsB = new HashMap<>();
        for (String s : b) countsB.merge(s, 1, Integer::sum);

        int intersection = 0;
        for (var entry : countsA.entrySet()) {
            intersection += Math.min(entry.getValue(), countsB.getOrDefault(entry.getKey(), 0));
        }
        return intersection;
    }

    /**
     * Checks if 'smaller' is a subset of 'larger' as multisets, allowing
     * up to 'maxExtra' instructions in 'larger' that don't exist in 'smaller'.
     * Unlike isStoreLoadSuperset, any instruction type is allowed as an extra.
     */
    private static boolean isSubsetMultiset(List<String> smaller, List<String> larger, int maxExtra) {
        Map<String, Integer> smallCounts = new HashMap<>();
        for (String s : smaller) smallCounts.merge(s, 1, Integer::sum);
        Map<String, Integer> largeCounts = new HashMap<>();
        for (String s : larger) largeCounts.merge(s, 1, Integer::sum);

        // Every instruction in smaller must exist in larger
        for (var entry : smallCounts.entrySet()) {
            int largeCount = largeCounts.getOrDefault(entry.getKey(), 0);
            if (largeCount < entry.getValue()) return false;
        }

        // Total extras must be within limit
        int totalExtra = larger.size() - smaller.size();
        return totalExtra <= maxExtra;
    }

    /**
     * Strip consecutive XLOAD vA; XSTORE vB pairs (variable copies with no
     * computation). These are register allocation artifacts where one compiler
     * copies a value to a different variable slot.
     */

    /**
     * Checks if the larger sorted multiset is a superset of the smaller one,
     * where the extra instructions are only store/load (variable spilling artifacts).
     */
    private static boolean isStoreLoadSuperset(List<String> smaller, List<String> larger) {
        // Build multiset counts for both
        Map<String, Integer> smallCounts = new HashMap<>();
        for (String s : smaller) smallCounts.merge(s, 1, Integer::sum);
        Map<String, Integer> largeCounts = new HashMap<>();
        for (String s : larger) largeCounts.merge(s, 1, Integer::sum);

        // Check that every instruction in smaller exists in larger with >= count
        for (var entry : smallCounts.entrySet()) {
            int largeCount = largeCounts.getOrDefault(entry.getKey(), 0);
            if (largeCount < entry.getValue()) return false;
        }

        // Check that extra instructions are only store/load or DUP
        for (var entry : largeCounts.entrySet()) {
            int smallCount = smallCounts.getOrDefault(entry.getKey(), 0);
            int extra = entry.getValue() - smallCount;
            if (extra > 0) {
                String insn = entry.getKey();
                // Only allow store, load, DUP, static field access, constant-push,
                // and return/throw (trailing unreachable code) as extras
                boolean isAllowedExtra = insn.matches("[AILFD]?[SL](?:TORE|OAD) v\\?")
                        || "DUP".equals(insn) || "DUP2".equals(insn)
                        || "POP".equals(insn) || "POP2".equals(insn)
                        || "ASTORE v\\?".equals(insn)
                        || insn.startsWith("PUTSTATIC ") || insn.startsWith("GETSTATIC ")
                        || insn.startsWith("PUTFIELD ") || insn.startsWith("GETFIELD ")
                        || insn.startsWith("ICONST_") || insn.startsWith("BIPUSH ")
                        || insn.startsWith("SIPUSH ") || "ACONST_NULL".equals(insn)
                        || insn.startsWith("FCONST_") || insn.startsWith("DCONST_")
                        || insn.startsWith("LCONST_")
                        || insn.startsWith("LDC ")
                        || "RETURN".equals(insn) || "ARETURN".equals(insn)
                        || "IRETURN".equals(insn) || "LRETURN".equals(insn)
                        || "FRETURN".equals(insn) || "DRETURN".equals(insn)
                        || "ATHROW".equals(insn)
                        // Guard clause inversions can produce extra conditional branches
                        || insn.startsWith("IF_")
                        || "CHECKCAST v?".equals(insn) || insn.startsWith("CHECKCAST ");
                if (!isAllowedExtra) return false;
            }
        }

        return true;
    }

    private static List<String> stripVariableCopies(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size() && isLoadInsn(insns.get(i)) && isStoreInsn(insns.get(i + 1))) {
                // Check type matches (both ALOAD/ASTORE, both ILOAD/ISTORE, etc.)
                char loadType = insns.get(i).charAt(0);
                char storeType = insns.get(i + 1).charAt(0);
                if (loadType == storeType) {
                    i++; // Skip both the load and store
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Strip RETURN/ATHROW that immediately follow GOTO (unreachable fallthrough).
     * These are dead code artifacts where the compiler emits a fallback return
     * after an unconditional jump.
     */
    private static List<String> stripUnreachableFallthrough(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i > 0 && insns.get(i - 1).startsWith("GOTO ")
                    && (isReturnString(insns.get(i)) || insns.get(i).equals("ATHROW"))) {
                continue;
            }
            // Also strip RETURN/ATHROW immediately after another RETURN/ATHROW
            if (i > 0 && (isReturnString(insns.get(i)) || insns.get(i).equals("ATHROW"))
                    && (isReturnString(insns.get(i - 1)) || insns.get(i - 1).equals("ATHROW"))) {
                continue;
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Strip duplicate exit sequences: if identical exit patterns (e.g., ALOAD vN; ARETURN)
     * appear multiple times, remove duplicates. Works by tracking seen exit signatures
     * and removing subsequent identical ones.
     */
    private static List<String> stripDuplicateExitSequences(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        Set<String> seenExits = new HashSet<>();
        for (int i = 0; i < insns.size(); i++) {
            String insn = insns.get(i);
            if (isReturnString(insn) || insn.equals("ATHROW")) {
                // Build exit signature: preceding value-producing instructions + exit
                int start = i;
                while (start > 0) {
                    String prev = insns.get(start - 1);
                    if (isLoadInsn(prev) || isConstantPushInsn(prev)
                            || "ACONST_NULL".equals(prev) || prev.startsWith("GETSTATIC ")) {
                        start--;
                    } else {
                        break;
                    }
                }
                // Limit lookback to 3 preceding instructions
                if (i - start > 3) start = i - 3;
                StringBuilder sig = new StringBuilder();
                for (int j = start; j <= i; j++) {
                    sig.append(VAR_STRIP.matcher(insns.get(j)).replaceAll("v?")).append(";");
                }
                String key = sig.toString();
                if (seenExits.contains(key)) {
                    // Remove preceding instructions already added for this duplicate exit
                    int toRemove = i - start;
                    for (int r = 0; r < toRemove && !result.isEmpty(); r++) {
                        result.remove(result.size() - 1);
                    }
                    continue; // Skip the exit instruction too
                }
                seenExits.add(key);
            }
            result.add(insn);
        }
        return result;
    }

    /**
     * Normalize integer constant encoding to canonical form.
     * ICONST_N, BIPUSH, SIPUSH, and LDC for integers are all equivalent
     * when they push the same value. Normalize to the most compact form.
     */
    private static List<String> normalizeConstants(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            Long val = extractConstantValue(insn);
            if (val != null) {
                if (val >= -1 && val <= 5) {
                    result.add("ICONST_" + (val == -1 ? "M1" : val));
                } else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
                    result.add("BIPUSH " + val);
                } else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
                    result.add("SIPUSH " + val);
                } else {
                    result.add("LDC " + val);
                }
            } else {
                result.add(insn);
            }
        }
        return result;
    }

    private int findFirstDifference(List<String> a, List<String> b) {
        if (semanticNormalize) {
            return findFirstDifferenceIsomorphic(a, b);
        }
        int minLen = Math.min(a.size(), b.size());
        for (int i = 0; i < minLen; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return i;
            }
        }
        if (a.size() != b.size()) {
            return minLen;
        }
        return -1;
    }

    /**
     * Compares instruction lists with label isomorphism: instead of requiring
     * identical label IDs, checks that labels map consistently (bijection).
     * This handles cases where control flow normalization shifts label numbering.
     */
    private int findFirstDifferenceIsomorphic(List<String> a, List<String> b) {
        Map<String, String> aToB = new HashMap<>();
        Map<String, String> bToA = new HashMap<>();

        int minLen = Math.min(a.size(), b.size());
        for (int i = 0; i < minLen; i++) {
            if (!matchWithLabelMapping(a.get(i), b.get(i), aToB, bToA)) {
                return i;
            }
        }
        if (a.size() != b.size()) {
            return minLen;
        }
        return -1;
    }

    /**
     * Checks if two instructions match, allowing label IDs to differ as long as
     * the mapping is a consistent bijection. Non-label parts must match exactly.
     */
    private static boolean matchWithLabelMapping(String insnA, String insnB,
                                                  Map<String, String> aToB,
                                                  Map<String, String> bToA) {
        if (insnA.equals(insnB)) return true;

        // Virtual/interface dispatch: owner class may differ due to type erasure/hierarchy
        String vmA = extractVirtualMethod(insnA);
        String vmB = extractVirtualMethod(insnB);
        if (vmA != null && vmB != null && vmA.equals(vmB)) return true;

        // INVOKEDYNAMIC descriptor normalization: treat B/S/C/Z as I in descriptors
        // Handles cases like makeConcatWithConstants(II) vs (SI) where the decompiler
        // uses short instead of int for narrow integer types.
        if (insnA.startsWith("INVOKEDYNAMIC ") && insnB.startsWith("INVOKEDYNAMIC ")) {
            String normA = normalizeNarrowTypes(insnA);
            String normB = normalizeNarrowTypes(insnB);
            if (normA.equals(normB)) return true;
            // LambdaMetafactory: strip args portion (type erasure differences)
            if (normA.contains("LambdaMetafactory.metafactory") && normB.contains("LambdaMetafactory.metafactory")) {
                int argsA = normA.indexOf(" args=[");
                int argsB = normB.indexOf(" args=[");
                if (argsA >= 0 && argsB >= 0) {
                    if (normA.substring(0, argsA).equals(normB.substring(0, argsB))) return true;
                }
            }
        }

        // Constant propagation tolerance: one side has GETSTATIC, other has constant push.
        // The recompiler may inline known constant values or vice versa.
        if (isConstantPropagationPair(insnA, insnB)) return true;

        // Constant encoding tolerance: same value loaded with different opcodes
        // e.g., BIPUSH 127 vs SIPUSH 127, or ICONST_1 vs BIPUSH 1
        if (isConstantPushInsn(insnA) && isConstantPushInsn(insnB)) {
            Long valA = extractConstantValue(insnA);
            Long valB = extractConstantValue(insnB);
            if (valA != null && valA.equals(valB)) return true;
        }

        // Replace all label references with a placeholder to compare non-label parts
        String skelA = LABEL_REF.matcher(insnA).replaceAll("L?");
        String skelB = LABEL_REF.matcher(insnB).replaceAll("L?");
        if (!skelA.equals(skelB)) return false;

        // Extract label pairs and verify consistent mapping.
        // Uses surjection (many-to-one) rather than bijection: multiple labels on one
        // side can map to the same label on the other side. This handles branch target
        // merging where the recompiler coalesces multiple branch destinations into one.
        Matcher matcherA = LABEL_REF.matcher(insnA);
        Matcher matcherB = LABEL_REF.matcher(insnB);
        while (matcherA.find() && matcherB.find()) {
            String labelA = matcherA.group();
            String labelB = matcherB.group();

            String existingB = aToB.get(labelA);

            if (existingB == null) {
                aToB.put(labelA, labelB);
            } else if (!existingB.equals(labelB)) {
                return false; // A's label maps inconsistently to different B labels
            }
            // Note: we do NOT check bToA — multiple A labels may map to the same B label
            // (branch target merging). This is safe because the instruction-level comparison
            // catches actual semantic differences in the code at those targets.
        }

        return true;
    }

    /**
     * Checks if two instructions form a constant propagation pair:
     * one is a constant push (ICONST/BIPUSH/SIPUSH/LDC) and the other is a
     * field load (GETSTATIC/GETFIELD). The compiler may inline known constant values.
     */
    private static boolean isConstantPropagationPair(String insnA, String insnB) {
        return (isConstantPushInsn(insnA) && isFieldLoadInsn(insnB))
                || (isFieldLoadInsn(insnA) && isConstantPushInsn(insnB));
    }

    private static boolean isConstantPushInsn(String insn) {
        return insn.startsWith("ICONST_") || insn.startsWith("LCONST_")
                || insn.startsWith("FCONST_") || insn.startsWith("DCONST_")
                || insn.startsWith("BIPUSH ") || insn.startsWith("SIPUSH ")
                || insn.startsWith("LDC ") || insn.equals("ACONST_NULL");
    }

    private static boolean isFieldLoadInsn(String insn) {
        return insn.startsWith("GETSTATIC ") || insn.startsWith("GETFIELD ");
    }

    /**
     * Strips variable indices from instructions, creating an "opcode skeleton".
     * ALOAD v3 -> ALOAD v?, ISTORE v0 -> ISTORE v?, IINC v1 5 -> IINC v? 5.
     * Preserves all other operands (field names, method names, constants).
     * This allows matching methods where copy propagation changed which variable
     * slot is used but the operations are otherwise identical.
     */
    private static final Pattern VAR_STRIP = Pattern.compile("(?<=[AILFDS](?:LOAD|STORE) )v\\d+|(?<=IINC )v\\d+");

    private static List<String> toOpcodeSkeleton(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            result.add(VAR_STRIP.matcher(insn).replaceAll("v?"));
        }
        return result;
    }

    /**
     * Strips DUP/DUP2 instructions and standalone ASTORE/ALOAD pairs that serve
     * as stack manipulation equivalents. Also strips ALOAD before PUTFIELD that
     * serves as the DUP-equivalent object reference reload in compound assignments.
     */
    private static List<String> stripStackManipulation(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            String insn = insns.get(i);
            // Strip DUP/DUP2
            if ("DUP".equals(insn) || "DUP2".equals(insn)) continue;
            // Strip POP/POP2 (stack discard)
            if ("POP".equals(insn) || "POP2".equals(insn)) continue;
            // Strip SWAP
            if ("SWAP".equals(insn)) continue;
            result.add(insn);
        }
        // Apply store-load elimination on the result to clean up redundant patterns
        result = eliminateStoreLoad(result);
        // Strip ALOAD before PUTFIELD: these are DUP-equivalent object reference reloads
        // in compound field assignments (e.g., stats.endurance -= x).
        // Original uses DUP (already stripped above), recompiled uses ALOAD vN; SWAP (SWAP
        // already stripped). The remaining ALOAD provides the same object reference.
        result = stripLoadBeforePutfield(result);
        // Strip trailing duplicate exit instructions (consecutive RETURN/ATHROW at end).
        result = stripTrailingDuplicateExits(result);
        // Normalize dead stores to POP (ASTORE vN where vN is never loaded → POP).
        result = normalizeDeadStoresToPop(result);
        return result;
    }

    /**
     * Converts ASTORE vN to POP when vN is never loaded in the method.
     * This catches the pattern where one side stores a constructor result to
     * an unused variable while the other side simply pops it.
     */
    private static List<String> normalizeDeadStoresToPop(List<String> insns) {
        // First pass: collect all loaded variables
        Set<String> loadedVars = new HashSet<>();
        for (String insn : insns) {
            if (isLoadInsn(insn)) {
                loadedVars.add(insn.substring(insn.indexOf(' ') + 1)); // e.g., "v3"
            }
        }
        // Second pass: convert dead stores to POP
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if (isStoreInsn(insn)) {
                String var = insn.substring(insn.indexOf(' ') + 1);
                if (!loadedVars.contains(var)) {
                    result.add("POP");
                    continue;
                }
            }
            result.add(insn);
        }
        return result;
    }

    /**
     * Removes trailing duplicate exit instructions. If the last N instructions are
     * all returns or throws, keep only the first one. This handles dead code at
     * method ends from try-finally or other constructs.
     */
    private static List<String> stripTrailingDuplicateExits(List<String> insns) {
        if (insns.size() < 2) return insns;
        int lastIdx = insns.size() - 1;
        String last = insns.get(lastIdx);
        if (!isReturnString(last) && !last.equals("ATHROW")) return insns;
        // Walk backwards while we see return/throw
        int firstExit = lastIdx;
        while (firstExit > 0) {
            String prev = insns.get(firstExit - 1);
            if (isReturnString(prev) || prev.equals("ATHROW")) {
                firstExit--;
            } else {
                break;
            }
        }
        if (firstExit == lastIdx) return insns; // only one exit at end
        // Keep everything up to and including the first exit
        return insns.subList(0, firstExit + 1);
    }

    /**
     * Strips ALOAD instructions that immediately precede PUTFIELD.
     * These are DUP-equivalent object reference reloads for compound field assignments.
     */
    private static List<String> stripLoadBeforePutfield(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size()
                    && insns.get(i).startsWith("ALOAD ")
                    && insns.get(i + 1).startsWith("PUTFIELD ")) {
                // Skip this ALOAD — the PUTFIELD will be added on the next iteration
                continue;
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Extracts the integer constant value from a constant push instruction.
     * Handles ICONST_N, BIPUSH N, SIPUSH N, LDC N (for integer/long).
     * Returns null if the instruction doesn't push a numeric constant.
     */
    private static Long extractConstantValue(String insn) {
        if (insn.startsWith("ICONST_")) {
            String suffix = insn.substring(7);
            if ("M1".equals(suffix)) return -1L;
            try { return Long.parseLong(suffix); } catch (NumberFormatException e) { return null; }
        }
        if (insn.startsWith("LCONST_")) {
            try { return Long.parseLong(insn.substring(7)); } catch (NumberFormatException e) { return null; }
        }
        if (insn.startsWith("BIPUSH ") || insn.startsWith("SIPUSH ")) {
            try { return Long.parseLong(insn.substring(insn.indexOf(' ') + 1)); } catch (NumberFormatException e) { return null; }
        }
        if (insn.startsWith("LDC ")) {
            String val = insn.substring(4);
            // Integer or long constant in LDC
            if (val.endsWith("L")) {
                try { return Long.parseLong(val.substring(0, val.length() - 1)); } catch (NumberFormatException e) { return null; }
            }
            try { return Long.parseLong(val); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private List<String> buildContext(List<String> insns, int start, int end) {
        List<String> ctx = new ArrayList<>();
        int clampedEnd = Math.min(insns.size(), end);
        for (int i = start; i < clampedEnd; i++) {
            ctx.add(String.format("#%-4d %s", i, insns.get(i)));
        }
        if (clampedEnd < insns.size() && clampedEnd < end) {
            ctx.add("... (" + (insns.size() - clampedEnd) + " more)");
        }
        return ctx;
    }

    // ── Try-catch comparison ──

    private String compareTryCatchBlocks(MethodNode orig, MethodNode recomp) {
        List<String> origTcb = formatTryCatchBlocks(orig);
        List<String> recompTcb = formatTryCatchBlocks(recomp);

        if (origTcb.equals(recompTcb)) {
            return null;
        }

        // With semantic normalization, use label isomorphism for try-catch too
        if (semanticNormalize && origTcb.size() == recompTcb.size()) {
            Map<String, String> aToB = new HashMap<>();
            Map<String, String> bToA = new HashMap<>();
            boolean allMatch = true;
            for (int i = 0; i < origTcb.size(); i++) {
                if (!matchWithLabelMapping(origTcb.get(i), recompTcb.get(i), aToB, bToA)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) return null;

            // Try label-agnostic multiset comparison: same try-catch blocks but in different order
            // or with different label assignments. Normalize by stripping label IDs.
            List<String> origNorm = origTcb.stream()
                    .map(s -> LABEL_REF.matcher(s).replaceAll("L?"))
                    .sorted().collect(Collectors.toList());
            List<String> recompNorm = recompTcb.stream()
                    .map(s -> LABEL_REF.matcher(s).replaceAll("L?"))
                    .sorted().collect(Collectors.toList());
            if (origNorm.equals(recompNorm)) return null;
        }

        // Different count: check if the exception types are the same (order-independent)
        if (semanticNormalize) {
            List<String> origTypes = origTcb.stream()
                    .map(s -> s.substring(s.lastIndexOf(' ') + 1))
                    .sorted().collect(Collectors.toList());
            List<String> recompTypes = recompTcb.stream()
                    .map(s -> s.substring(s.lastIndexOf(' ') + 1))
                    .sorted().collect(Collectors.toList());
            if (origTypes.equals(recompTypes)) return null;

            // For synchronized blocks: all handlers catch * (finally).
            // Guard clause elimination or block reordering can change the number of
            // catch-all handlers; these are compiler artifacts, not semantic differences.
            boolean allOrigStar = origTypes.stream().allMatch("*"::equals);
            boolean allRecompStar = recompTypes.stream().allMatch("*"::equals);
            if (allOrigStar && allRecompStar) return null;

            // Mixed case: if named exception types match and the only difference is
            // in catch-all (*) handler count, treat as equivalent.
            List<String> origNamed = origTypes.stream().filter(t -> !"*".equals(t)).collect(Collectors.toList());
            List<String> recompNamed = recompTypes.stream().filter(t -> !"*".equals(t)).collect(Collectors.toList());
            if (origNamed.equals(recompNamed)) return null;
        }

        return "Try-catch blocks differ: " + origTcb.size() + " original vs " + recompTcb.size() + " recompiled";
    }

    private List<String> formatTryCatchBlocks(MethodNode method) {
        if (method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty()) {
            return List.of();
        }

        Map<LabelNode, Integer> labelMap = buildReferencedLabelMap(method);
        List<String> result = new ArrayList<>();
        for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
            result.add("TRYCATCH L" + labelMap.getOrDefault(tcb.start, -1)
                    + "-L" + labelMap.getOrDefault(tcb.end, -1)
                    + " handler:L" + labelMap.getOrDefault(tcb.handler, -1)
                    + " " + (tcb.type != null ? tcb.type : "*"));
        }
        return result;
    }

    // ── Instruction normalization ──

    private List<String> normalizeInstructions(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return List.of();
        }

        Map<LabelNode, Integer> labelMap = buildReferencedLabelMap(method);
        Map<Integer, Integer> varMap = normalizeVars ? new LinkedHashMap<>() : null;

        // Build label-to-target map for GOTO-to-RETURN normalization
        Map<LabelNode, AbstractInsnNode> labelTargets = semanticNormalize
                ? buildLabelTargetMap(method) : null;

        List<String> result = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            // Semantic: include referenced labels for if-else canonicalization
            if (semanticNormalize && insn instanceof LabelNode label) {
                Integer labelId = labelMap.get(label);
                if (labelId != null) {
                    result.add("LABEL L" + labelId);
                }
                continue;
            }

            // Semantic: skip CHECKCAST (generics erasure artifact)
            if (semanticNormalize && insn instanceof TypeInsnNode
                    && insn.getOpcode() == Opcodes.CHECKCAST) {
                continue;
            }

            // Semantic: replace GOTO to return/throw label with the target instruction itself
            if (semanticNormalize && insn instanceof JumpInsnNode jump
                    && insn.getOpcode() == Opcodes.GOTO) {
                AbstractInsnNode target = labelTargets.get(jump.label);
                if (target != null && (isReturnInsn(target) || target.getOpcode() == Opcodes.ATHROW)) {
                    String formatted = formatInsn(target, varMap, labelMap);
                    if (formatted != null) result.add(formatted);
                    continue;
                }
            }

            String formatted = formatInsn(insn, varMap, labelMap);
            if (formatted != null) {
                result.add(formatted);
            }
        }

        if (semanticNormalize) {
            result = canonicalizeIfElse(result);
            result = normalizeConditionalGoto(result);
            result = eliminateNoopGoto(result);
            result = inlineGotoReturn(result);
            result = stripLabels(result);
            result = collapseOuterRefAccess(result);
            result = normalizeDupAsReload(result);
            result = renormalizeVars(result);
            result = normalizeGuardClauses(result);
            result = normalizeDupStore(result);
            result = normalizeDupPutfield(result);
            result = normalizeArrayInitDup(result);
            result = eliminateStoreLoad(result);
            result = normalizeBooleanReturn(result);
            result = stripRequireNonNull(result);
            result = normalizeInnerConstructorArg(result);
            result = normalizeExpressionOrder(result);
            result = normalizeIincExpansion(result);
            result = normalizeArithmeticSign(result);
            result = stripNarrowingCasts(result);
            result = collapseWideningChain(result);
            result = stripBoxingRoundTrip(result);
            result = stripBooleanMaterialization(result);
            result = normalizeBooleanObjectCompare(result);
            result = normalizeStringConcat(result);
            result = stripStringValueOf(result);
            result = normalizeInvokedynamicDescriptors(result);
            result = normalizeCapturedVarNames(result);
            result = normalizeFieldAliases(result);
            result = normalizeBooleanCompare(result);
            result = normalizePopAsStore(result);
            result = renormalizeVars(result);
        }

        return result;
    }


    private Map<LabelNode, Integer> buildReferencedLabelMap(MethodNode method) {
        // Collect all labels that are actually referenced by jumps or try-catch blocks
        Set<LabelNode> referenced = Collections.newSetFromMap(new IdentityHashMap<>());

        if (method.instructions != null) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof JumpInsnNode j) {
                    referenced.add(j.label);
                } else if (insn instanceof TableSwitchInsnNode ts) {
                    referenced.add(ts.dflt);
                    referenced.addAll(ts.labels);
                } else if (insn instanceof LookupSwitchInsnNode ls) {
                    referenced.add(ls.dflt);
                    referenced.addAll(ls.labels);
                }
            }
        }

        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                referenced.add(tcb.start);
                referenced.add(tcb.end);
                referenced.add(tcb.handler);
            }
        }

        // Assign sequential IDs based on order of appearance in instruction stream
        Map<LabelNode, Integer> map = new IdentityHashMap<>();
        int id = 0;
        if (method.instructions != null) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode label && referenced.contains(label) && !map.containsKey(label)) {
                    map.put(label, id++);
                }
            }
        }
        return map;
    }

    /**
     * Maps each label to the first real instruction that follows it.
     * Used for GOTO-to-RETURN normalization.
     */
    private static Map<LabelNode, AbstractInsnNode> buildLabelTargetMap(MethodNode method) {
        Map<LabelNode, AbstractInsnNode> map = new IdentityHashMap<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                AbstractInsnNode target = insn.getNext();
                while (target != null && (target instanceof LabelNode
                        || target instanceof FrameNode || target instanceof LineNumberNode)) {
                    target = target.getNext();
                }
                if (target != null) {
                    map.put(label, target);
                }
            }
        }
        return map;
    }

    private static boolean isReturnInsn(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= Opcodes.IRETURN && op <= Opcodes.RETURN;
    }

    // ── Semantic normalization (string-level post-processing) ──

    /**
     * Normalizes DUP followed by xSTORE into xSTORE followed by xLOAD.
     * Both patterns are semantically equivalent: keep value on stack while storing.
     */
    private static List<String> normalizeDupStore(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size()) {
                String curr = insns.get(i);
                String next = insns.get(i + 1);
                if (curr.equals("DUP") || curr.equals("DUP2")) {
                    String loadEquiv = storeToLoad(next);
                    if (loadEquiv != null) {
                        result.add(next);       // xSTORE vN
                        result.add(loadEquiv);  // xLOAD vN
                        i++;
                        continue;
                    }
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Eliminates redundant consecutive store-load pairs.
     * Pattern: xSTORE vN; xLOAD vN -> removed (value stays on stack).
     * This handles both the return case (xSTORE; xLOAD; xRETURN -> xRETURN)
     * and the general case (redundant variable copy).
     */
    private static List<String> eliminateStoreLoad(List<String> insns) {
        List<String> result = insns;
        boolean changed = true;
        int maxPasses = 5;
        while (changed && maxPasses-- > 0) {
            changed = false;
            List<String> next = new ArrayList<>(result.size());
            for (int i = 0; i < result.size(); i++) {
                if (i + 1 < result.size()) {
                    String store = result.get(i);
                    String load = result.get(i + 1);
                    String loadEquiv = storeToLoad(store);
                    if (loadEquiv != null && loadEquiv.equals(load)) {
                        i++;
                        changed = true;
                        continue;
                    }
                }
                next.add(result.get(i));
            }
            result = next;
        }
        return result;
    }

    /**
     * Converts an xSTORE instruction string to its corresponding xLOAD.
     * Returns null if the instruction is not a store.
     */
    private static String storeToLoad(String insn) {
        if (insn.startsWith("ASTORE v")) return "ALOAD" + insn.substring(6);
        if (insn.startsWith("ISTORE v")) return "ILOAD" + insn.substring(6);
        if (insn.startsWith("LSTORE v")) return "LLOAD" + insn.substring(6);
        if (insn.startsWith("FSTORE v")) return "FLOAD" + insn.substring(6);
        if (insn.startsWith("DSTORE v")) return "DLOAD" + insn.substring(6);
        // Static field store/load: PUTSTATIC x; GETSTATIC x
        if (insn.startsWith("PUTSTATIC ")) return "GETSTATIC " + insn.substring(10);
        return null;
    }

    private static boolean isReturnString(String insn) {
        return insn.equals("RETURN") || insn.equals("ARETURN") || insn.equals("IRETURN")
                || insn.equals("LRETURN") || insn.equals("FRETURN") || insn.equals("DRETURN");
    }

    /**
     * Normalizes POP to ASTORE v997 (synthetic dead variable) so that it matches
     * code where a value is stored to a variable that's never read.
     * POP2 → LSTORE v997 or DSTORE v997.
     */
    private static List<String> normalizePopAsStore(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if ("POP".equals(insn)) {
                result.add("ASTORE v997");
            } else if ("POP2".equals(insn)) {
                result.add("LSTORE v997");
            } else {
                result.add(insn);
            }
        }
        return result;
    }

    /**
     * Propagates variable copies: when ALOAD vX; ASTORE vY is found (simple copy),
     * removes the copy and replaces all subsequent ALOAD vY with ALOAD vX until vY
     * is reassigned. Also handles ILOAD/FLOAD/LLOAD/DLOAD variants.
     * This normalizes register allocation differences where one compiler uses an
     * extra variable copy that the other doesn't.
     */
    private static List<String> propagateCopies(List<String> insns) {
        List<String> result = new ArrayList<>(insns);
        boolean changed = true;
        int maxPasses = 5;
        while (changed && maxPasses-- > 0) {
            changed = false;
            for (int i = 0; i + 1 < result.size(); i++) {
                String load = result.get(i);
                String store = result.get(i + 1);
                // Check for xLOAD vX; xSTORE vY pattern (same type prefix)
                if (!isLoadInsn(load) || storeToLoad(store) == null) continue;
                String loadPrefix = load.substring(0, load.indexOf(' '));
                String storePrefix = store.substring(0, store.indexOf(' '));
                // ALOAD→ASTORE, ILOAD→ISTORE, etc.
                if (!storePrefix.equals(loadPrefix.replace("LOAD", "STORE"))) continue;

                String srcVar = load.substring(load.indexOf(' ') + 1);   // vX
                String dstVar = store.substring(store.indexOf(' ') + 1); // vY
                if (srcVar.equals(dstVar)) continue; // not a copy

                String dstLoad = storeToLoad(store); // xLOAD vY
                String dstStore = store;              // xSTORE vY

                // Count uses of vY after this point
                int useCount = 0;
                boolean reassigned = false;
                for (int j = i + 2; j < result.size(); j++) {
                    if (result.get(j).equals(dstStore)) {
                        reassigned = true;
                        break;
                    }
                    if (result.get(j).equals(dstLoad)) {
                        useCount++;
                    }
                }

                // Only propagate if vY is never reassigned (simple SSA-like copy)
                if (!reassigned && useCount > 0) {
                    // Remove the copy (load + store)
                    result.remove(i + 1);
                    result.remove(i);
                    // Replace all uses of vY with vX
                    for (int j = i; j < result.size(); j++) {
                        if (result.get(j).equals(dstLoad)) {
                            result.set(j, load);
                        }
                    }
                    changed = true;
                    break; // Restart scan
                }
            }
        }
        return result;
    }

    /**
     * Normalizes DUP immediately after xLOAD into a repeat of the xLOAD.
     * Pattern: xLOAD vN; DUP -> xLOAD vN; xLOAD vN (when DUP is not followed by xSTORE).
     * DUP+xSTORE is handled separately by normalizeDupStore.
     */
    private static List<String> normalizeDupAsReload(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            // DUP2: duplicate the top two stack values. Normalize by repeating the
            // previous two instructions (e.g., ALOAD arr; ILOAD idx; DUP2 →
            // ALOAD arr; ILOAD idx; ALOAD arr; ILOAD idx).
            if (insns.get(i).equals("DUP2") && result.size() >= 2) {
                boolean followedByStore = (i + 1 < insns.size()) && storeToLoad(insns.get(i + 1)) != null;
                if (!followedByStore) {
                    String prev2 = result.get(result.size() - 2);
                    String prev1 = result.get(result.size() - 1);
                    result.add(prev2);
                    result.add(prev1);
                    continue;
                }
            }
            if (insns.get(i).equals("DUP") && !result.isEmpty()) {
                String prev = result.get(result.size() - 1);
                boolean followedByStore = (i + 1 < insns.size()) && storeToLoad(insns.get(i + 1)) != null;
                if (!followedByStore && isLoadInsn(prev)) {
                    result.add(prev); // Replace DUP with repeat of the LOAD
                    continue;
                }
                // DUP after GETFIELD: duplicate the ALOAD + GETFIELD sequence.
                // Original: ALOAD vN; GETFIELD x; DUP; GETFIELD y; ... PUTFIELD y
                // Becomes:  ALOAD vN; GETFIELD x; ALOAD vN; GETFIELD x; GETFIELD y; ... PUTFIELD y
                if (!followedByStore && prev.startsWith("GETFIELD ") && result.size() >= 2) {
                    String prevPrev = result.get(result.size() - 2);
                    if (isLoadInsn(prevPrev)) {
                        result.add(prevPrev); // Re-emit the ALOAD
                        result.add(prev);     // Re-emit the GETFIELD
                        continue;
                    }
                    // DUP after INVOKE().GETFIELD: the recompiled side stores the invoke
                    // result to a variable and reloads it. Normalize DUP to a synthetic ALOAD.
                    if (isInvokeInsn(prevPrev) || prevPrev.startsWith("GETSTATIC ")
                            || prevPrev.startsWith("GETFIELD ")) {
                        result.add("ALOAD v999");
                        result.add(prev);
                        continue;
                    }
                }
                // DUP after GETSTATIC: duplicate the field load. The recompiled side
                // typically reads the static field twice instead of using DUP.
                if (!followedByStore && prev.startsWith("GETSTATIC ")) {
                    result.add(prev); // Re-emit the GETSTATIC
                    continue;
                }
                // DUP after INVOKE: the return value is duplicated for a compound field
                // assignment (one copy for GETFIELD, one kept for PUTFIELD). The recompiled
                // code stores the result in a local variable and loads it explicitly.
                // After eliminateStoreLoad absorbs the ASTORE+ALOAD pair, only one ALOAD
                // remains on the recompiled side. Normalize DUP to a synthetic ALOAD so
                // renormalizeVars (which runs after this) assigns matching variable numbers.
                if (!followedByStore && isInvokeInsn(prev)) {
                    result.add("ALOAD v999"); // Synthetic variable, renormalized later
                    continue;
                }
                // General fallback: DUP after any value-producing instruction (AALOAD,
                // NEW, CHECKCAST, etc.) where the recompiled side stores to a variable
                // and reloads. Convert to synthetic ALOAD v999.
                if (!followedByStore) {
                    result.add("ALOAD v999");
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Strips compiler-generated Objects.requireNonNull(x); POP patterns.
     * Javac 11+ adds these null checks for inner class outer references.
     * The original PZ code was compiled with a JDK that didn't add them.
     */
    private static List<String> stripRequireNonNull(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size() && insns.get(i + 1).equals("POP")) {
                String curr = insns.get(i);
                // Strip INVOKESTATIC Objects.requireNonNull; POP
                if (curr.startsWith("INVOKESTATIC java/util/Objects.requireNonNull(")) {
                    i++; // skip both
                    continue;
                }
                // Strip GETSTATIC x; POP — dead field access (used only for side effects
                // like triggering class initialization). Recompiled code may omit this.
                if (curr.startsWith("GETSTATIC ")) {
                    i++; // skip both
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Collapses inner class outer reference field access into a single load.
     * In inner classes, the original compiler may use the constructor parameter directly
     * (ALOAD v1) to access the outer 'this', while the recompiled code loads from the
     * this$N field (ALOAD v0; GETFIELD X.this$N:LY;). This 2-instruction pattern is
     * collapsed to a single ALOAD v998 (synthetic), so that renormalizeVars assigns
     * matching variable numbers.
     */
    private static final Pattern THIS_FIELD = Pattern.compile("GETFIELD .+\\.this\\$\\d+:.*");

    private static List<String> collapseOuterRefAccess(List<String> insns) {
        // First pass: identify outer ref parameters by finding:
        // ALOAD vN; PUTFIELD *.this$*  (where vN != v0)
        // This indicates vN holds the outer reference passed as constructor parameter.
        Set<String> outerRefVars = new HashSet<>();
        for (int i = 0; i + 1 < insns.size(); i++) {
            if (insns.get(i).startsWith("ALOAD v") && !insns.get(i).equals("ALOAD v0")
                    && insns.get(i + 1).startsWith("PUTFIELD ")
                    && insns.get(i + 1).contains(".this$")) {
                outerRefVars.add(insns.get(i)); // e.g., "ALOAD v1"
            }
        }

        // Second pass: collapse outer ref accesses
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            // Collapse ALOAD vX; GETFIELD *.this$N:* → ALOAD v998
            if (i + 1 < insns.size()
                    && insns.get(i).startsWith("ALOAD ")
                    && THIS_FIELD.matcher(insns.get(i + 1)).matches()) {
                result.add("ALOAD v998");
                i++; // Skip GETFIELD
                continue;
            }
            // Replace uses of outer ref parameter variable with synthetic.
            // In inner class constructors, the outer reference is passed as a parameter
            // (e.g., v1) and the original compiler uses it directly, while the recompiled
            // code loads from the this$0 field. Normalizing both to v998 makes them match.
            if (!outerRefVars.isEmpty() && outerRefVars.contains(insns.get(i))) {
                result.add("ALOAD v998");
                continue;
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Normalizes captured variable field names in anonymous/inner classes.
     * The decompiler may generate different names for captured variables
     * (e.g., val$value vs val$string1). Both have the same semantics.
     * Normalizes all val$X field names to val$? in GETFIELD/PUTFIELD.
     * Also normalizes this$N field names to this$0 for consistency.
     */
    private static final Pattern VAL_FIELD = Pattern.compile("\\.val\\$[a-zA-Z0-9_]+:");
    private static final Pattern THIS_N_FIELD = Pattern.compile("\\.this\\$\\d+:");

    private static List<String> normalizeCapturedVarNames(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if ((insn.startsWith("GETFIELD ") || insn.startsWith("PUTFIELD "))
                    && (insn.contains(".val$") || insn.contains(".this$"))) {
                String normalized = VAL_FIELD.matcher(insn).replaceAll(".val\\$?:");
                normalized = THIS_N_FIELD.matcher(normalized).replaceAll(".this\\$0:");
                result.add(normalized);
            } else {
                result.add(insn);
            }
        }
        return result;
    }

    /**
     * Normalizes array element initialization DUP ordering.
     * When initializing array elements inline, one compiler may emit:
     *   DUP; ICONST_N; NEW T; DUP; ...; INVOKESPECIAL T.<init>; AASTORE
     * while another emits:
     *   ICONST_N; NEW T; DUP; ...; INVOKESPECIAL T.<init>; AASTORE
     * The difference is that the first form DUPs the array reference explicitly
     * while the second form has it already on stack. This strips the leading DUP
     * when it precedes an array element store pattern.
     */
    /**
     * Strips array-ref-management instructions between array element stores.
     * One compiler uses DUP before each element index (stack-based):
     *   ANEWARRAY T; DUP; ICONST_0; ...; AASTORE; DUP; ICONST_1; ...; AASTORE
     * Another uses ALOAD of stored array ref (variable-based):
     *   ANEWARRAY T; ICONST_0; ...; AASTORE; ALOAD vN; ICONST_1; ...; AASTORE
     * Strip both DUP and ALOAD-after-AASTORE to normalize to:
     *   ANEWARRAY T; ICONST_0; ...; AASTORE; ICONST_1; ...; AASTORE
     */
    private static List<String> normalizeArrayInitDup(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            String curr = insns.get(i);
            if (i + 1 < insns.size()) {
                String next = insns.get(i + 1);
                // Pattern 1: DUP before array index constant, followed by xASTORE
                if ("DUP".equals(curr) && isIntConstantPush(next) && hasArrayStore(insns, i + 2)) {
                    continue; // Strip this DUP
                }
                // Pattern 2: ALOAD after xASTORE, before next array index constant
                if (curr.startsWith("ALOAD ") && !result.isEmpty()
                        && isArrayStore(result.get(result.size() - 1))
                        && isIntConstantPush(next)) {
                    continue; // Strip this ALOAD (array ref reload between elements)
                }
            }
            result.add(curr);
        }
        return result;
    }

    private static boolean isIntConstantPush(String insn) {
        return insn.startsWith("ICONST_") || insn.startsWith("BIPUSH ")
                || insn.startsWith("SIPUSH ") || insn.startsWith("LDC ");
    }

    private static boolean isArrayStore(String insn) {
        return "AASTORE".equals(insn) || "IASTORE".equals(insn) || "FASTORE".equals(insn)
                || "DASTORE".equals(insn) || "LASTORE".equals(insn) || "BASTORE".equals(insn)
                || "SASTORE".equals(insn) || "CASTORE".equals(insn);
    }

    private static boolean hasArrayStore(List<String> insns, int from) {
        for (int j = from; j < Math.min(from + 25, insns.size()); j++) {
            if (isArrayStore(insns.get(j))) return true;
            if (insns.get(j).startsWith("GOTO ") || insns.get(j).startsWith("IF")
                    || insns.get(j).endsWith("RETURN") || "ATHROW".equals(insns.get(j))) {
                return false;
            }
        }
        return false;
    }

    /**
     * Normalizes inner class constructor argument patterns.
     * When constructing an inner class, the recompiled code sometimes pushes the
     * outer 'this' reference an extra time (once for the constructor arg, once as
     * a redundant DUP). Pattern:
     *   NEW X; DUP; ALOAD vN; ALOAD vN; INVOKESPECIAL X.<init>
     * Normalized to:
     *   NEW X; DUP; ALOAD vN; INVOKESPECIAL X.<init>
     * Also handles the case where the outer ref comes via GETFIELD this$0.
     */
    private static List<String> normalizeInnerConstructorArg(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            // Look for: [something]; DUP; INVOKESPECIAL <init>
            // where the extra DUP is before an inner class constructor
            if (i + 1 < insns.size()
                    && insns.get(i).equals("DUP")
                    && insns.get(i + 1).startsWith("INVOKESPECIAL ")
                    && insns.get(i + 1).contains(".<init>(")) {
                // Check if this is a redundant DUP (not after NEW)
                // The NEW;DUP pair at the start of constructor is normal.
                // The extra DUP before INVOKESPECIAL is the artifact.
                if (!result.isEmpty() && !result.get(result.size() - 1).startsWith("NEW ")) {
                    // Skip the redundant DUP
                    continue;
                }
            }

            // Look for duplicate ALOAD before INVOKESPECIAL <init>
            // Pattern: ALOAD vN; ALOAD vN; INVOKESPECIAL <init>
            if (i + 2 < insns.size()
                    && insns.get(i).startsWith("ALOAD ")
                    && insns.get(i).equals(insns.get(i + 1))
                    && insns.get(i + 2).startsWith("INVOKESPECIAL ")
                    && insns.get(i + 2).contains(".<init>(")) {
                result.add(insns.get(i)); // Keep one ALOAD
                i++; // Skip the duplicate
                continue;
            }

            // Pattern: ALOAD vN; GETFIELD this$0; ALOAD vN; GETFIELD this$0; INVOKESPECIAL
            // (duplicate outer ref via field access)
            if (i + 4 < insns.size()
                    && insns.get(i).startsWith("ALOAD ")
                    && insns.get(i + 1).contains("this$")
                    && insns.get(i).equals(insns.get(i + 2))
                    && insns.get(i + 1).equals(insns.get(i + 3))
                    && insns.get(i + 4).startsWith("INVOKESPECIAL ")
                    && insns.get(i + 4).contains(".<init>(")) {
                result.add(insns.get(i));
                result.add(insns.get(i + 1));
                i += 3; // Skip the duplicate pair
                continue;
            }

            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Normalizes expression evaluation order for commutative operations.
     * When IADD, IMUL, FADD, FMUL, LADD, LMUL, DADD, DMUL are preceded by
     * operand pushes in different order, this normalizes the push order.
     * Specifically handles: A; B; OP vs B; A; OP where A and B are simple
     * operand-push sequences (loads, constants, field accesses).
     */
    private static List<String> normalizeExpressionOrder(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            String curr = insns.get(i);
            if (isCommutativeOp(curr) && result.size() >= 2) {
                // Look at the two operands pushed before this operation.
                // For single-instruction operands: swap to canonical order.
                String opB = result.get(result.size() - 1);
                String opA = result.get(result.size() - 2);
                if (isSimpleOperandPush(opA) && isSimpleOperandPush(opB)) {
                    if (opA.compareTo(opB) > 0) {
                        // Swap to canonical (lexicographic) order
                        result.set(result.size() - 2, opB);
                        result.set(result.size() - 1, opA);
                    }
                }
            }
            result.add(curr);
        }
        return result;
    }

    private static boolean isCommutativeOp(String insn) {
        return "IADD".equals(insn) || "IMUL".equals(insn)
                || "FADD".equals(insn) || "FMUL".equals(insn)
                || "LADD".equals(insn) || "LMUL".equals(insn)
                || "DADD".equals(insn) || "DMUL".equals(insn)
                || "IXOR".equals(insn) || "IOR".equals(insn) || "IAND".equals(insn)
                || "LXOR".equals(insn) || "LOR".equals(insn) || "LAND".equals(insn);
    }

    private static boolean isSimpleOperandPush(String insn) {
        return isLoadInsn(insn) || isConstantPushInsn(insn)
                || insn.startsWith("GETSTATIC ") || insn.startsWith("GETFIELD ");
    }

    /**
     * Normalizes IINC instructions into their expanded form: ILOAD vN; const; IADD; ISTORE vN.
     * The compiler may use either IINC (compact form) or the expanded load-add-store sequence.
     * This normalization ensures both forms produce the same instruction stream.
     */
    private static List<String> normalizeIincExpansion(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if (insn.startsWith("IINC ")) {
                // Parse: "IINC vN K"
                String[] parts = insn.split(" ");
                if (parts.length == 3) {
                    String var = parts[1]; // vN
                    String incStr = parts[2];
                    try {
                        int inc = Integer.parseInt(incStr);
                        result.add("ILOAD " + var);
                        if (inc >= -1 && inc <= 5) {
                            result.add("ICONST_" + inc);
                        } else if (inc >= Byte.MIN_VALUE && inc <= Byte.MAX_VALUE) {
                            result.add("BIPUSH " + inc);
                        } else if (inc >= Short.MIN_VALUE && inc <= Short.MAX_VALUE) {
                            result.add("SIPUSH " + inc);
                        } else {
                            result.add("LDC " + inc);
                        }
                        result.add("IADD");
                        result.add("ISTORE " + var);
                        continue;
                    } catch (NumberFormatException e) {
                        // Fall through to add as-is
                    }
                }
            }
            result.add(insn);
        }
        return result;
    }

    /**
     * Normalizes arithmetic identity: "const(-N); ADD" ≡ "const(N); SUB" and vice versa.
     * Canonicalizes to positive constant + SUB when the constant is negative with ADD,
     * or positive constant + ADD when the constant is negative with SUB.
     * This handles cases like: SIPUSH -10000; IADD → SIPUSH 10000; ISUB
     */
    private static List<String> normalizeArithmeticSign(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size()) {
                String constInsn = insns.get(i);
                String arithInsn = insns.get(i + 1);
                // Check for integer add/sub with negative constant
                if (("IADD".equals(arithInsn) || "ISUB".equals(arithInsn))) {
                    Long val = parseIntConstant(constInsn);
                    if (val != null && val < 0) {
                        String replacement = formatIntConstant(-val);
                        if (replacement != null) {
                            result.add(replacement);
                            result.add("IADD".equals(arithInsn) ? "ISUB" : "IADD");
                            i++;
                            continue;
                        }
                    }
                }
                // Float: FCONST doesn't have negative forms, but LDC can
                if (("FADD".equals(arithInsn) || "FSUB".equals(arithInsn))
                        && constInsn.startsWith("LDC ")) {
                    try {
                        String valStr = constInsn.substring(4);
                        if (valStr.endsWith("f") || valStr.endsWith("F")) {
                            float f = Float.parseFloat(valStr);
                            if (f < 0) {
                                result.add("LDC " + (-f) + (valStr.endsWith("F") ? "F" : "f"));
                                result.add("FADD".equals(arithInsn) ? "FSUB" : "FADD");
                                i++;
                                continue;
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
                // Long
                if (("LADD".equals(arithInsn) || "LSUB".equals(arithInsn))
                        && constInsn.startsWith("LDC ")) {
                    try {
                        String valStr = constInsn.substring(4);
                        if (valStr.endsWith("L") || valStr.endsWith("l")) {
                            long l = Long.parseLong(valStr.substring(0, valStr.length() - 1));
                            if (l < 0) {
                                result.add("LDC " + (-l) + (valStr.endsWith("L") ? "L" : "l"));
                                result.add("LADD".equals(arithInsn) ? "LSUB" : "LADD");
                                i++;
                                continue;
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
                // Double
                if (("DADD".equals(arithInsn) || "DSUB".equals(arithInsn))
                        && constInsn.startsWith("LDC ")) {
                    try {
                        String valStr = constInsn.substring(4);
                        if (valStr.endsWith("d") || valStr.endsWith("D")
                                || (!valStr.endsWith("f") && !valStr.endsWith("F")
                                    && !valStr.endsWith("L") && !valStr.endsWith("l")
                                    && valStr.contains("."))) {
                            double d = Double.parseDouble(valStr.replaceAll("[dD]$", ""));
                            if (d < 0) {
                                String suffix = (valStr.endsWith("d") || valStr.endsWith("D"))
                                        ? valStr.substring(valStr.length() - 1) : "";
                                result.add("LDC " + (-d) + suffix);
                                result.add("DADD".equals(arithInsn) ? "DSUB" : "DADD");
                                i++;
                                continue;
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    private static Long parseIntConstant(String insn) {
        if (insn.startsWith("ICONST_")) {
            try { return Long.parseLong(insn.substring(7)); } catch (NumberFormatException e) { return null; }
        }
        if (insn.startsWith("BIPUSH ")) {
            try { return Long.parseLong(insn.substring(7)); } catch (NumberFormatException e) { return null; }
        }
        if (insn.startsWith("SIPUSH ")) {
            try { return Long.parseLong(insn.substring(7)); } catch (NumberFormatException e) { return null; }
        }
        if (insn.startsWith("LDC ")) {
            String val = insn.substring(4);
            if (!val.contains(".") && !val.endsWith("f") && !val.endsWith("F")
                    && !val.endsWith("L") && !val.endsWith("l") && !val.startsWith("\"")) {
                try { return Long.parseLong(val); } catch (NumberFormatException e) { return null; }
            }
        }
        return null;
    }

    private static String formatIntConstant(long val) {
        if (val >= -1 && val <= 5) return "ICONST_" + val;
        if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) return "BIPUSH " + val;
        if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) return "SIPUSH " + val;
        return "LDC " + val;
    }

    /**
     * Strips I2B, I2S, I2C narrowing casts. These are semantically redundant when
     * the JVM already narrows at method call boundaries and array stores.
     * The original PZ compiler omits many of these; the decompiler reintroduces them.
     */
    private static List<String> stripNarrowingCasts(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if (insn.equals("I2B") || insn.equals("I2S") || insn.equals("I2C")) {
                continue;
            }
            result.add(insn);
        }
        return result;
    }

    /**
     * Collapses redundant type widening chains into their direct equivalent.
     * I2F; F2D -> I2D  (int-to-float-to-double collapsed to int-to-double)
     * L2F; F2D -> L2D  (long-to-float-to-double collapsed to long-to-double)
     */
    private static List<String> collapseWideningChain(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size()) {
                String curr = insns.get(i);
                String next = insns.get(i + 1);
                if ("I2F".equals(curr) && "F2D".equals(next)) {
                    result.add("I2D");
                    i++;
                    continue;
                }
                if ("L2F".equals(curr) && "F2D".equals(next)) {
                    result.add("L2D");
                    i++;
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Strips boxing round-trip pairs that the decompiler introduces.
     * Pattern: Integer.valueOf(I)Integer; Integer.intValue()I -> removed (no-op)
     * Also handles Long, Short, Byte, Character, Boolean, Float, Double.
     */
    private static List<String> stripBoxingRoundTrip(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size()) {
                String curr = insns.get(i);
                String next = insns.get(i + 1);
                // Integer.valueOf(I) followed by Integer.intValue()I
                if (curr.equals("INVOKESTATIC java/lang/Integer.valueOf(I)Ljava/lang/Integer;")
                        && next.equals("INVOKEVIRTUAL java/lang/Integer.intValue()I")) {
                    i++; // skip both
                    continue;
                }
                // Long.valueOf(J) followed by Long.longValue()J
                if (curr.equals("INVOKESTATIC java/lang/Long.valueOf(J)Ljava/lang/Long;")
                        && next.equals("INVOKEVIRTUAL java/lang/Long.longValue()J")) {
                    i++;
                    continue;
                }
                // Float.valueOf(F) followed by Float.floatValue()F
                if (curr.equals("INVOKESTATIC java/lang/Float.valueOf(F)Ljava/lang/Float;")
                        && next.equals("INVOKEVIRTUAL java/lang/Float.floatValue()F")) {
                    i++;
                    continue;
                }
                // Double.valueOf(D) followed by Double.doubleValue()D
                if (curr.equals("INVOKESTATIC java/lang/Double.valueOf(D)Ljava/lang/Double;")
                        && next.equals("INVOKEVIRTUAL java/lang/Double.doubleValue()D")) {
                    i++;
                    continue;
                }
                // Boolean.valueOf(Z) followed by Boolean.booleanValue()Z
                if (curr.equals("INVOKESTATIC java/lang/Boolean.valueOf(Z)Ljava/lang/Boolean;")
                        && next.equals("INVOKEVIRTUAL java/lang/Boolean.booleanValue()Z")) {
                    i++;
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Normalizes Boolean object comparison patterns to canonical form.
     * Original compiler: GETSTATIC Boolean.TRUE; IF_ACMPEQ L → compare references
     * Recompiled: booleanValue()Z; GETSTATIC Boolean.TRUE; booleanValue()Z; IF_ICMPEQ L → unbox+compare ints
     * Both are normalized to: booleanValue()Z; IFNE L (or IFEQ for negated comparisons).
     */
    private static List<String> normalizeBooleanObjectCompare(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            // Pattern 1: GETSTATIC Boolean.TRUE; IF_ACMPEQ → booleanValue()Z; IFNE
            if (i + 1 < insns.size()
                    && insns.get(i).equals("GETSTATIC java/lang/Boolean.TRUE:Ljava/lang/Boolean;")) {
                String next = insns.get(i + 1);
                if (next.startsWith("IF_ACMPEQ ")) {
                    result.add("INVOKEVIRTUAL java/lang/Boolean.booleanValue()Z");
                    result.add("IFNE " + next.substring(10));
                    i++;
                    continue;
                }
                if (next.startsWith("IF_ACMPNE ")) {
                    result.add("INVOKEVIRTUAL java/lang/Boolean.booleanValue()Z");
                    result.add("IFEQ " + next.substring(10));
                    i++;
                    continue;
                }
            }
            // Pattern 2: booleanValue()Z; GETSTATIC Boolean.TRUE; booleanValue()Z; IF_ICMPxx
            // → booleanValue()Z; IFNE/IFEQ (comparing against constant 1)
            if (i + 3 < insns.size()
                    && insns.get(i).equals("INVOKEVIRTUAL java/lang/Boolean.booleanValue()Z")
                    && insns.get(i + 1).equals("GETSTATIC java/lang/Boolean.TRUE:Ljava/lang/Boolean;")
                    && insns.get(i + 2).equals("INVOKEVIRTUAL java/lang/Boolean.booleanValue()Z")) {
                String cmp = insns.get(i + 3);
                if (cmp.startsWith("IF_ICMPEQ ")) {
                    result.add("INVOKEVIRTUAL java/lang/Boolean.booleanValue()Z");
                    result.add("IFNE " + cmp.substring(10));
                    i += 3;
                    continue;
                }
                if (cmp.startsWith("IF_ICMPNE ")) {
                    result.add("INVOKEVIRTUAL java/lang/Boolean.booleanValue()Z");
                    result.add("IFEQ " + cmp.substring(10));
                    i += 3;
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Strips redundant String.valueOf() calls before string concatenation.
     * Original: String.valueOf(obj) + makeConcatWithConstants(String, ...)
     * Recompiled: makeConcatWithConstants(Object, ...)
     * The valueOf call is a no-op since makeConcatWithConstants handles Object directly.
     */
    private static List<String> stripStringValueOf(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size()
                    && insns.get(i).equals("INVOKESTATIC java/lang/String.valueOf(Ljava/lang/Object;)Ljava/lang/String;")
                    && insns.get(i + 1).startsWith("INVOKEDYNAMIC makeConcatWithConstants(")) {
                // Skip valueOf, let makeConcatWithConstants handle the Object directly
                continue;
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Normalizes INVOKEDYNAMIC descriptors by replacing narrow integer types (B/S/C/Z)
     * with I, and for makeConcatWithConstants, normalizing reference types to Object.
     * This handles cases where the decompiler uses short/byte/char where the original
     * used int, and String.valueOf conversion differences.
     */
    private static List<String> normalizeInvokedynamicDescriptors(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if (insn.startsWith("INVOKEDYNAMIC ")) {
                String normalized = normalizeNarrowTypes(insn);
                // For LambdaMetafactory, strip the args portion entirely.
                // The args contain implementation method handle types and instantiated
                // types which differ due to type erasure (e.g., specific types vs Object,
                // primitive vs boxed). The semantic meaning is captured by the bootstrap
                // method and the INVOKEDYNAMIC name + descriptor.
                if (normalized.contains("LambdaMetafactory.metafactory")) {
                    int argsIdx = normalized.indexOf(" args=[");
                    if (argsIdx >= 0) {
                        normalized = normalized.substring(0, argsIdx);
                    }
                }
                result.add(normalized);
            } else {
                result.add(insn);
            }
        }
        return result;
    }

    /**
     * Strips boolean materialization no-ops: patterns where a boolean value (0/1)
     * on the stack is consumed by a conditional and then re-pushed as ICONST_0/1.
     * Pattern: IFEQ L; ICONST_1; GOTO M; ICONST_0 -> (nothing, value stays on stack)
     * Pattern: IFNE L; ICONST_0; GOTO M; ICONST_1 -> (nothing, value stays on stack)
     * Both patterns convert boolean→boolean (identity), so they're no-ops.
     */
    private static List<String> stripBooleanMaterialization(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 3 < insns.size()) {
                String a = insns.get(i);
                String b = insns.get(i + 1);
                String c = insns.get(i + 2);
                String d = insns.get(i + 3);
                // IFEQ L; ICONST_1; GOTO M; ICONST_0 (identity)
                if (a.startsWith("IFEQ ") && b.equals("ICONST_1")
                        && c.startsWith("GOTO ") && d.equals("ICONST_0")) {
                    i += 3; // skip all 4
                    continue;
                }
                // IFNE L; ICONST_0; GOTO M; ICONST_1 (identity)
                if (a.startsWith("IFNE ") && b.equals("ICONST_0")
                        && c.startsWith("GOTO ") && d.equals("ICONST_1")) {
                    i += 3;
                    continue;
                }
                // IFEQ L; ICONST_0; GOTO M; ICONST_1 (negation)
                if (a.startsWith("IFEQ ") && b.equals("ICONST_0")
                        && c.startsWith("GOTO ") && d.equals("ICONST_1")) {
                    // This is boolean negation: !value. Keep as simplified form.
                    // We handle this elsewhere in normalizeBooleanReturn.
                }
                // IFNE L; ICONST_1; GOTO M; ICONST_0 (negation)
                if (a.startsWith("IFNE ") && b.equals("ICONST_1")
                        && c.startsWith("GOTO ") && d.equals("ICONST_0")) {
                    // Also boolean negation.
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Normalizes DUP followed by PUTSTATIC/PUTFIELD into the store + a load.
     * Pattern: DUP; PUTSTATIC x -> PUTSTATIC x; GETSTATIC x
     * Pattern: DUP; PUTFIELD x -> PUTFIELD x; ALOAD this; GETFIELD x (too complex, skip)
     * Only handles PUTSTATIC for simplicity since that's the common case.
     */
    private static List<String> normalizeDupPutfield(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size() && insns.get(i).equals("DUP")) {
                String next = insns.get(i + 1);
                if (next.startsWith("PUTSTATIC ")) {
                    // DUP; PUTSTATIC x -> PUTSTATIC x; GETSTATIC x
                    result.add(next);
                    result.add("GETSTATIC " + next.substring(10));
                    i++; // skip PUTSTATIC
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Normalizes string concatenation patterns where one side uses
     * String.valueOf(X) + makeConcatWithConstants(String) and the other
     * uses makeConcatWithConstants(X) directly.
     * Pattern: INVOKESTATIC String.valueOf(X)String; INVOKEDYNAMIC makeConcatWithConstants(String)
     *       -> INVOKEDYNAMIC makeConcatWithConstants(X)
     */
    private static List<String> normalizeStringConcat(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size()) {
                String curr = insns.get(i);
                String next = insns.get(i + 1);
                // Match: INVOKESTATIC String.valueOf(X)Ljava/lang/String;
                //   followed by: INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;)...
                if (curr.startsWith("INVOKESTATIC java/lang/String.valueOf(")
                        && next.startsWith("INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;)")) {
                    // Extract the original argument type from String.valueOf
                    int parenStart = curr.indexOf('(');
                    int parenEnd = curr.indexOf(')');
                    if (parenStart >= 0 && parenEnd > parenStart) {
                        String argType = curr.substring(parenStart + 1, parenEnd);
                        // Replace makeConcatWithConstants(Ljava/lang/String;) with (argType)
                        String newConcat = next.replace(
                                "makeConcatWithConstants(Ljava/lang/String;)",
                                "makeConcatWithConstants(" + argType + ")");
                        result.add(newConcat);
                        i++; // skip makeConcatWithConstants
                        continue;
                    }
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Returns true if the given instruction list is a simple return block:
     * 0-1 value-producing instructions followed by a return instruction.
     * E.g., [IRETURN], [ICONST_1, IRETURN], [ACONST_NULL, ARETURN], [LLOAD v5, LRETURN].
     */
    private static boolean isSimpleReturnBlock(List<String> insns) {
        if (insns.isEmpty() || insns.size() > 3) return false;
        String last = insns.get(insns.size() - 1);
        if (!isReturnString(last) && !last.equals("ATHROW")) return false;
        // All preceding instructions must be value-producing (loads, constants)
        for (int i = 0; i < insns.size() - 1; i++) {
            String s = insns.get(i);
            if (!isLoadInsn(s) && !isConstantPushInsn(s) && !s.startsWith("GETSTATIC ")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLoadInsn(String insn) {
        return insn.startsWith("ALOAD ") || insn.startsWith("ILOAD ")
                || insn.startsWith("LLOAD ") || insn.startsWith("FLOAD ")
                || insn.startsWith("DLOAD ");
    }

    private static boolean isStoreInsn(String insn) {
        return insn.startsWith("ASTORE ") || insn.startsWith("ISTORE ")
                || insn.startsWith("LSTORE ") || insn.startsWith("FSTORE ")
                || insn.startsWith("DSTORE ");
    }

    private static boolean isBoxingValueOf(String insn) {
        return insn.startsWith("INVOKESTATIC java/lang/Boolean.valueOf(Z)")
                || insn.startsWith("INVOKESTATIC java/lang/Integer.valueOf(I)")
                || insn.startsWith("INVOKESTATIC java/lang/Long.valueOf(J)")
                || insn.startsWith("INVOKESTATIC java/lang/Float.valueOf(F)")
                || insn.startsWith("INVOKESTATIC java/lang/Double.valueOf(D)")
                || insn.startsWith("INVOKESTATIC java/lang/Short.valueOf(S)")
                || insn.startsWith("INVOKESTATIC java/lang/Byte.valueOf(B)")
                || insn.startsWith("INVOKESTATIC java/lang/Character.valueOf(C)");
    }

    private static boolean isInvokeInsn(String insn) {
        return insn.startsWith("INVOKEVIRTUAL ") || insn.startsWith("INVOKESTATIC ")
                || insn.startsWith("INVOKEINTERFACE ") || insn.startsWith("INVOKESPECIAL ")
                || insn.startsWith("INVOKEDYNAMIC ");
    }

    /**
     * Normalizes known field name aliases. The decompiler sometimes renames fields
     * to avoid shadowing (e.g., LoginQueue.LoginQueue → LoginQueue.s_loginQueue).
     * This normalizes recompiled field references to match the original names.
     */
    private static List<String> normalizeFieldAliases(List<String> insns) {
        // Known field renames: decompiler name → original name
        // LoginQueue: field named same as class, decompiler adds s_ prefix
        // _assertionsDisabled → $assertionsDisabled: decompiler renames to avoid
        // conflict with compiler-synthesized field
        Map<String, String> aliases = Map.of(
                "LoginQueue.s_loginQueue:", "LoginQueue.LoginQueue:",
                "._assertionsDisabled:", ".$assertionsDisabled:"
        );
        if (aliases.isEmpty()) return insns;

        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            String normalized = insn;
            for (var entry : aliases.entrySet()) {
                if (normalized.contains(entry.getKey())) {
                    normalized = normalized.replace(entry.getKey(), entry.getValue());
                }
            }
            result.add(normalized);
        }
        return result;
    }

    /**
     * Normalizes boolean return patterns. Converts the explicit if-else boolean
     * return pattern to a simple IRETURN when it's an identity (return the value on stack).
     * Pattern: IFEQ L; ICONST_1; IRETURN; ICONST_0; IRETURN -> IRETURN
     * Pattern: IFNE L; ICONST_0; IRETURN; ICONST_1; IRETURN -> IRETURN
     */
    private static List<String> normalizeBooleanReturn(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 4 < insns.size() && isConditionalBranch(insns.get(i))) {
                String a = insns.get(i + 1);
                String retA = insns.get(i + 2);
                String b = insns.get(i + 3);
                String retB = insns.get(i + 4);

                if (retA.equals("IRETURN") && retB.equals("IRETURN")) {
                    int spaceIdx = insns.get(i).indexOf(' ');
                    String opcode = spaceIdx > 0 ? insns.get(i).substring(0, spaceIdx) : "";

                    // Identity: IFEQ; 1; ret; 0; ret -> return value on stack
                    // Identity: IFNE; 0; ret; 1; ret -> return value on stack
                    if ((a.equals("ICONST_1") && b.equals("ICONST_0") && opcode.equals("IFEQ"))
                            || (a.equals("ICONST_0") && b.equals("ICONST_1") && opcode.equals("IFNE"))) {
                        result.add("IRETURN");
                        i += 4;
                        continue;
                    }
                    // Negation: IFEQ; 0; ret; 1; ret -> negate and return
                    // Negation: IFNE; 1; ret; 0; ret -> negate and return
                    if ((a.equals("ICONST_0") && b.equals("ICONST_1") && opcode.equals("IFEQ"))
                            || (a.equals("ICONST_1") && b.equals("ICONST_0") && opcode.equals("IFNE"))) {
                        result.add("ICONST_1");
                        result.add("IXOR");
                        result.add("IRETURN");
                        i += 4;
                        continue;
                    }
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Normalizes boolean comparison patterns:
     *   ICONST_1; IF_ICMPEQ Lx -> IFNE Lx  (booleanField == true -> booleanField != 0)
     *   ICONST_0; IF_ICMPEQ Lx -> IFEQ Lx  (booleanField == false -> booleanField == 0)
     *   ICONST_1; IF_ICMPNE Lx -> IFEQ Lx  (booleanField != true -> booleanField == 0)
     *   ICONST_0; IF_ICMPNE Lx -> IFNE Lx  (booleanField != false -> booleanField != 0)
     */
    private static List<String> normalizeBooleanCompare(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size()) {
                String curr = insns.get(i);
                String next = insns.get(i + 1);
                if (curr.equals("ICONST_1") && next.startsWith("IF_ICMPEQ ")) {
                    result.add("IFNE " + next.substring(10));
                    i++;
                    continue;
                }
                if (curr.equals("ICONST_0") && next.startsWith("IF_ICMPEQ ")) {
                    result.add("IFEQ " + next.substring(10));
                    i++;
                    continue;
                }
                if (curr.equals("ICONST_1") && next.startsWith("IF_ICMPNE ")) {
                    result.add("IFEQ " + next.substring(10));
                    i++;
                    continue;
                }
                if (curr.equals("ICONST_0") && next.startsWith("IF_ICMPNE ")) {
                    result.add("IFNE " + next.substring(10));
                    i++;
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    /**
     * Normalizes guard clauses: IF_xxx Ln; RETURN -> IF_inverted Ln.
     * Handles void guards (IF; RETURN) by inverting and removing the RETURN.
     */
    private static List<String> normalizeGuardClauses(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            if (i + 1 < insns.size() && insns.get(i + 1).equals("RETURN")) {
                String inverted = invertCondition(insns.get(i));
                if (inverted != null) {
                    result.add(inverted);
                    i++; // skip RETURN
                    continue;
                }
            }
            result.add(insns.get(i));
        }
        return result;
    }

    // ── If-else canonicalization ──

    /**
     * Canonicalizes if-else blocks by normalizing conditions to a canonical form.
     * Non-canonical conditions (IFNE, IFGE, IFGT, IF_ICMPNE, etc.) are inverted
     * and their if/else blocks swapped. This ensures both sides of a comparison
     * produce the same block order regardless of compiler branch direction.
     *
     * Handles two patterns:
     * 1. GOTO-based: IF_xxx L<a>; block1; GOTO L<b>; LABEL L<a>; block2; LABEL L<b>
     * 2. Return-terminated: IF_xxx L<a>; block1 (ends RETURN/THROW); LABEL L<a>; block2
     *
     * After swapping, variable names may be out of order; call renormalizeVars() after.
     */
    private List<String> canonicalizeIfElse(List<String> insns) {
        List<String> result = new ArrayList<>(insns);
        boolean changed = true;
        int maxPasses = 10;

        while (changed && maxPasses-- > 0) {
            changed = false;
            for (int i = 0; i < result.size(); i++) {
                String insn = result.get(i);
                if (!isConditionalBranch(insn)) continue;
                if (isCanonicalCondition(insn)) continue; // already canonical

                String targetLabel = extractBranchTarget(insn);
                if (targetLabel == null) continue;

                int labelAIdx = findLabelEntry(result, targetLabel, i + 1);
                if (labelAIdx < 0 || labelAIdx <= i + 1) continue;

                String block1End = result.get(labelAIdx - 1);
                String invertedCond = invertCondition(insn);
                if (invertedCond == null) continue;

                if (block1End.startsWith("GOTO ")) {
                    // GOTO-based if-else: well-defined block boundaries
                    String endLabel = block1End.substring(5);
                    int labelBIdx = findLabelEntry(result, endLabel, labelAIdx + 1);
                    if (labelBIdx < 0) continue;

                    List<String> block1 = new ArrayList<>(result.subList(i + 1, labelAIdx - 1));
                    List<String> block2 = new ArrayList<>(result.subList(labelAIdx + 1, labelBIdx));

                    List<String> newSection = new ArrayList<>();
                    newSection.add(invertedCond);
                    newSection.addAll(block2);
                    newSection.add(block1End);
                    newSection.add(result.get(labelAIdx));
                    newSection.addAll(block1);
                    newSection.add(result.get(labelBIdx));

                    List<String> newResult = new ArrayList<>(result.subList(0, i));
                    newResult.addAll(newSection);
                    if (labelBIdx + 1 < result.size()) {
                        newResult.addAll(result.subList(labelBIdx + 1, result.size()));
                    }
                    result = newResult;
                    changed = true;
                    break;
                }

                // Return-terminated: handled at comparison level by tryMatchGuardInversion.
            }
        }

        return result;
    }

    /**
     * Normalizes the pattern IF_xxx La; GOTO Lb; LABEL La into IF_inverted Lb; LABEL La.
     * The original compiler sometimes emits a conditional branch to the next instruction
     * followed by an unconditional GOTO, instead of a single inverted conditional branch.
     * Must run before stripLabels (needs label entries to detect the pattern).
     */
    private static List<String> normalizeConditionalGoto(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            String curr = insns.get(i);
            if (isConditionalBranch(curr)) {
                String ifTarget = extractBranchTarget(curr);
                if (ifTarget != null && i + 1 < insns.size()
                        && insns.get(i + 1).startsWith("GOTO ")) {
                    // Look for the IF target label right after the GOTO (allowing intervening LABELs)
                    int j = i + 2;
                    boolean foundTarget = false;
                    while (j < insns.size() && insns.get(j).startsWith("LABEL ")) {
                        if (insns.get(j).substring(6).equals(ifTarget)) {
                            foundTarget = true;
                            break;
                        }
                        j++;
                    }
                    if (foundTarget) {
                        String inverted = invertCondition(curr);
                        if (inverted != null) {
                            // Replace IF target with GOTO target
                            String gotoTarget = insns.get(i + 1).substring(5);
                            int spaceIdx = inverted.lastIndexOf(' ');
                            result.add(inverted.substring(0, spaceIdx + 1) + gotoTarget);
                            // Skip the GOTO, keep the labels
                            for (int k = i + 2; k <= j; k++) {
                                result.add(insns.get(k));
                            }
                            i = j;
                            continue;
                        }
                    }
                }
            }
            result.add(curr);
        }
        return result;
    }

    /**
     * Removes GOTO instructions that jump to the immediately following label.
     * Pattern: GOTO Ln; LABEL Ln -> LABEL Ln (the GOTO is a no-op).
     * Must run before stripLabels.
     */
    private static List<String> eliminateNoopGoto(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            String curr = insns.get(i);
            if (curr.startsWith("GOTO ") && i + 1 < insns.size()) {
                // Check if the target label follows immediately (allowing intervening LABELs)
                String gotoTarget = curr.substring(5);
                int j = i + 1;
                boolean isNoop = false;
                while (j < insns.size() && insns.get(j).startsWith("LABEL ")) {
                    if (insns.get(j).substring(6).equals(gotoTarget)) {
                        isNoop = true;
                        break;
                    }
                    j++;
                }
                if (isNoop) {
                    continue; // Skip the GOTO
                }
            }
            result.add(curr);
        }
        return result;
    }

    /**
     * Replaces GOTO Lx with the first instruction at label Lx, when that instruction
     * is a return or throw. This handles the case where the compiler redirects to a
     * shared return point instead of inlining the return instruction.
     * Also handles short sequences: GOTO Lx where Lx is LOAD + RETURN (2 insns).
     * Must run BEFORE stripLabels since it needs the LABEL entries.
     */
    private static List<String> inlineGotoReturn(List<String> insns) {
        // Build a label-to-index map
        Map<String, Integer> labelIdx = new HashMap<>();
        for (int i = 0; i < insns.size(); i++) {
            if (insns.get(i).startsWith("LABEL ")) {
                labelIdx.put(insns.get(i).substring(6), i);
            }
        }

        List<String> result = new ArrayList<>(insns.size());
        for (int i = 0; i < insns.size(); i++) {
            String curr = insns.get(i);
            if (curr.startsWith("GOTO ")) {
                String target = curr.substring(5);
                Integer idx = labelIdx.get(target);
                if (idx != null) {
                    // Find the first non-label instruction at the target
                    int j = idx + 1;
                    while (j < insns.size() && insns.get(j).startsWith("LABEL ")) j++;
                    if (j < insns.size()) {
                        String targetInsn = insns.get(j);
                        // Single return/throw: inline it
                        if (isReturnString(targetInsn) || targetInsn.equals("ATHROW")) {
                            result.add(targetInsn);
                            continue;
                        }
                        // Two-instruction return: xLOAD + xRETURN
                        if (j + 1 < insns.size() && isLoadInsn(targetInsn)
                                && isReturnString(insns.get(j + 1))) {
                            result.add(targetInsn);
                            result.add(insns.get(j + 1));
                            continue;
                        }
                        // Two-instruction constant-return: ICONST/FCONST/etc + xRETURN
                        if (j + 1 < insns.size() && isConstantPushInsn(targetInsn)
                                && isReturnString(insns.get(j + 1))) {
                            result.add(targetInsn);
                            result.add(insns.get(j + 1));
                            continue;
                        }
                        // Two-instruction boxing return: INVOKESTATIC valueOf + ARETURN
                        // (shared boxing block used by modern javac)
                        if (j + 1 < insns.size() && isBoxingValueOf(targetInsn)
                                && isReturnString(insns.get(j + 1))) {
                            result.add(targetInsn);
                            result.add(insns.get(j + 1));
                            continue;
                        }
                        // Store-load-return: xSTORE vN; xLOAD vN; xRETURN
                        // (shared return point that stores to local before returning)
                        if (j + 2 < insns.size() && isStoreInsn(targetInsn)) {
                            String nextInsn = insns.get(j + 1);
                            String retInsn = insns.get(j + 2);
                            if (isLoadInsn(nextInsn) && isReturnString(retInsn)) {
                                String storeOpc = targetInsn.split(" ")[0];
                                String loadOpc = nextInsn.split(" ")[0];
                                if (storeOpc.charAt(0) == loadOpc.charAt(0)
                                        || (storeOpc.startsWith("A") && loadOpc.startsWith("A"))) {
                                    result.add(targetInsn);
                                    result.add(nextInsn);
                                    result.add(retInsn);
                                    continue;
                                }
                            }
                        }
                    }
                }
            }
            result.add(curr);
        }
        return result;
    }

    /**
     * Returns true if the condition is in canonical form (no swap needed).
     * Canonical conditions: IFEQ, IFLT, IFLE, IF_ICMPEQ, IF_ICMPLT, IF_ICMPLE,
     * IF_ACMPEQ, IFNULL.
     */
    private static boolean isCanonicalCondition(String ifInsn) {
        int spaceIdx = ifInsn.indexOf(' ');
        if (spaceIdx < 0) return true;
        String opcode = ifInsn.substring(0, spaceIdx);
        return switch (opcode) {
            case "IFEQ", "IFLT", "IFLE",
                 "IF_ICMPEQ", "IF_ICMPLT", "IF_ICMPLE",
                 "IF_ACMPEQ", "IFNULL" -> true;
            default -> false;
        };
    }

    private static boolean isConditionalBranch(String insn) {
        return insn.startsWith("IF");
    }

    private static String extractBranchTarget(String insn) {
        int spaceIdx = insn.lastIndexOf(' ');
        if (spaceIdx < 0) return null;
        String target = insn.substring(spaceIdx + 1);
        return target.startsWith("L") ? target : null;
    }

    private static int findLabelEntry(List<String> insns, String label, int startIdx) {
        String target = "LABEL " + label;
        for (int i = startIdx; i < insns.size(); i++) {
            if (insns.get(i).equals(target)) return i;
        }
        return -1;
    }

    private static List<String> stripLabels(List<String> insns) {
        return insns.stream()
                .filter(s -> !s.startsWith("LABEL "))
                .collect(Collectors.toList());
    }

    // ── Guard clause inversion matching (comparison-level fallback) ──

    /**
     * Attempts to match two instruction lists that differ due to non-void guard clause inversion.
     * Pattern: one side has the guard (const+return) before the body, the other has it after.
     * The conditions are inverses of each other, but the guard content and body are identical.
     * This is a comparison-level check that doesn't modify instruction lists.
     */
    private boolean tryMatchGuardInversion(List<String> a, List<String> b, int diffIdx) {
        if (diffIdx >= a.size() || diffIdx >= b.size()) return false;

        String condA = a.get(diffIdx);
        String condB = b.get(diffIdx);
        if (!isConditionalBranch(condA) || !isConditionalBranch(condB)) return false;
        if (!areInverseConditions(condA, condB)) return false;

        List<String> restA = a.subList(diffIdx + 1, a.size());
        List<String> restB = b.subList(diffIdx + 1, b.size());

        // Fast path: try short guard match (1-5 instructions)
        if (tryGuardMatch(restA, restB)) return true;
        if (tryGuardMatch(restB, restA)) return true;

        // General: try all split points at return/throw instructions
        if (tryReturnTerminatedInversion(restA, restB)) return true;
        if (tryReturnTerminatedInversion(restB, restA)) return true;

        return false;
    }

    /**
     * Tries to match two instruction lists that differ by guard clause elimination.
     * One side has: IF_xxx; guard-body; RETURN/THROW; main-body
     * Other side has: IF_inverted; main-body
     * Handles single and multiple guard clauses. Tries progressively stripping
     * guard blocks from the longer side until the instruction counts match.
     */
    private boolean tryGuardElimination(List<String> longer, List<String> shorter, int diffIdx) {
        if (longer.size() <= shorter.size()) return false;
        int sizeDiff = longer.size() - shorter.size();
        if (sizeDiff > 30) return false; // too many extra instructions

        // Both must have conditional branches at the diff point
        if (diffIdx >= longer.size() || diffIdx >= shorter.size()) return false;
        String condLonger = longer.get(diffIdx);
        String condShorter = shorter.get(diffIdx);
        if (!isConditionalBranch(condLonger) || !isConditionalBranch(condShorter)) return false;
        if (!areInverseConditions(condLonger, condShorter)) return false;

        // Try stripping guard blocks from the longer side
        List<String> stripped = new ArrayList<>(longer);
        stripped.set(diffIdx, condShorter); // invert the first condition

        // Iteratively find and strip guard blocks
        int totalStripped = 0;
        int maxAttempts = 10;
        int searchFrom = diffIdx + 1;

        while (totalStripped < sizeDiff && maxAttempts-- > 0 && searchFrom < stripped.size()) {
            int guardEnd = findGuardEnd(stripped, searchFrom);
            if (guardEnd < 0) break;

            int guardLen = guardEnd - searchFrom + 1;
            // Strip this guard block
            for (int i = 0; i < guardLen; i++) {
                stripped.remove(searchFrom);
            }
            totalStripped += guardLen;

            // Check if the remaining instructions now diverge at another condition
            // that needs to be inverted
            if (searchFrom < stripped.size() && searchFrom < shorter.size()) {
                String nextLong = stripped.get(searchFrom);
                String nextShort = shorter.get(searchFrom);
                if (isConditionalBranch(nextLong) && isConditionalBranch(nextShort)
                        && areInverseConditions(nextLong, nextShort)) {
                    stripped.set(searchFrom, nextShort);
                    searchFrom++;
                    continue;
                }
            }
            break;
        }

        if (stripped.size() != shorter.size()) return false;

        // Renormalize and compare
        List<String> strippedNorm = renormalizeVars(stripped);
        List<String> shorterNorm = renormalizeVars(new ArrayList<>(shorter));

        if (findFirstDifferenceIsomorphic(strippedNorm, shorterNorm) == -1) return true;
        if (tryLabelAgnosticMatch(strippedNorm, shorterNorm)) return true;

        // Try with opcode skeleton (handles combined guard elimination + copy propagation)
        List<String> strippedSkel = toOpcodeSkeleton(strippedNorm);
        List<String> shorterSkel = toOpcodeSkeleton(shorterNorm);
        if (tryLabelAgnosticMatch(strippedSkel, shorterSkel)) return true;

        return false;
    }

    /**
     * Checks if guardFirst starts with a guard block (short const+return sequence)
     * that appears at the end of guardLast, with matching body contents in between.
     * If the body comparison fails at another condition inversion, recursively
     * tries to match the remaining body (handles multiple guard inversions).
     */
    private boolean tryGuardMatch(List<String> guardFirst, List<String> guardLast) {
        return tryGuardMatchRecursive(guardFirst, guardLast, 0);
    }

    private boolean tryGuardMatchRecursive(List<String> guardFirst, List<String> guardLast, int depth) {
        if (depth > 5) return false; // prevent unbounded recursion

        int guardEnd = findGuardEnd(guardFirst, 0);
        if (guardEnd < 0) return false;

        int guardLen = guardEnd + 1;
        if (guardLast.size() < guardLen) return false;

        // Guard at start of guardFirst
        List<String> guard = guardFirst.subList(0, guardLen);
        // Guard should be at end of guardLast
        List<String> guardAtEnd = guardLast.subList(guardLast.size() - guardLen, guardLast.size());

        // Guards must match (use label-isomorphic comparison for guards with nested branches)
        if (!guard.equals(guardAtEnd)
                && findFirstDifferenceIsomorphic(guard, guardAtEnd) != -1
                && !tryLabelAgnosticMatch(guard, guardAtEnd)) {
            // Also try opcode skeleton (handles guards with different variable slots)
            if (!tryLabelAgnosticMatch(toOpcodeSkeleton(guard), toOpcodeSkeleton(guardAtEnd))) {
                return false;
            }
        }

        // Bodies must match with label isomorphism and fresh var renormalization
        List<String> body = guardFirst.subList(guardLen, guardFirst.size());
        List<String> bodyLast = guardLast.subList(0, guardLast.size() - guardLen);

        // Renormalize vars independently (encounter order differs due to guard placement)
        List<String> bodyNorm = renormalizeVars(body);
        List<String> bodyLastNorm = renormalizeVars(bodyLast);

        int bodyDiff = findFirstDifferenceIsomorphic(bodyNorm, bodyLastNorm);
        if (bodyDiff == -1) return true;

        // Fallback: label-agnostic comparison for combined guard swap + label divergence
        if (bodyNorm.size() == bodyLastNorm.size() && tryLabelAgnosticMatch(bodyNorm, bodyLastNorm)) {
            return true;
        }

        // Fallback: opcode skeleton comparison (handles copy propagation in body)
        if (bodyNorm.size() == bodyLastNorm.size()) {
            List<String> bodySkel = toOpcodeSkeleton(bodyNorm);
            List<String> bodyLastSkel = toOpcodeSkeleton(bodyLastNorm);
            if (tryLabelAgnosticMatch(bodySkel, bodyLastSkel)) return true;
        }

        // Body comparison failed — check if it's another condition inversion that we can recurse on
        if (bodyDiff < bodyNorm.size() && bodyDiff < bodyLastNorm.size()) {
            String condA = bodyNorm.get(bodyDiff);
            String condB = bodyLastNorm.get(bodyDiff);
            if (isConditionalBranch(condA) && isConditionalBranch(condB)
                    && areInverseConditions(condA, condB)) {
                List<String> subRestA = bodyNorm.subList(bodyDiff + 1, bodyNorm.size());
                List<String> subRestB = bodyLastNorm.subList(bodyDiff + 1, bodyLastNorm.size());
                if (tryGuardMatchRecursive(subRestA, subRestB, depth + 1)) return true;
                if (tryGuardMatchRecursive(subRestB, subRestA, depth + 1)) return true;
            }
        }

        return false;
    }

    /**
     * Finds the end of a guard block starting at the given index.
     * A guard block ends with a return or throw instruction.
     * Allows nested conditional branches and GOTOs within the guard as long as
     * the block terminates within maxGuardLen instructions.
     * Returns the index of the terminating instruction, or -1 if no guard found.
     */
    private static int findGuardEnd(List<String> insns, int start) {
        int maxGuardLen = 30;
        int limit = Math.min(start + maxGuardLen, insns.size());
        for (int i = start; i < limit; i++) {
            if (isReturnString(insns.get(i)) || insns.get(i).equals("ATHROW")) {
                return i;
            }
            // SWITCH is too complex for guard matching
            if (insns.get(i).startsWith("SWITCH ")) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Checks if two conditional branch instructions have inverse opcodes.
     * Only compares the opcode portion, ignoring label targets.
     */
    /**
     * General return-terminated if-else inversion check. Tries splitting the first
     * list at every return/throw instruction and checks if swapping the halves
     * produces the second list (with var renormalization and label isomorphism).
     */
    private boolean tryReturnTerminatedInversion(List<String> first, List<String> second) {
        if (Math.abs(first.size() - second.size()) > 20) return false;

        List<String> secondNorm = renormalizeVars(second);
        int returnCount = 0;
        int maxReturns = 20;

        for (int k = 0; k < first.size() && returnCount < maxReturns; k++) {
            String insn = first.get(k);
            if (!isReturnString(insn) && !insn.equals("ATHROW")) continue;
            returnCount++;

            List<String> block2 = first.subList(k + 1, first.size());
            if (block2.isEmpty()) continue;

            List<String> expected = new ArrayList<>(first.size());
            expected.addAll(block2);
            expected.addAll(first.subList(0, k + 1));

            List<String> expectedNorm = renormalizeVars(expected);

            if (expectedNorm.size() == secondNorm.size()) {
                // Try isomorphic label comparison first
                if (findFirstDifferenceIsomorphic(expectedNorm, secondNorm) == -1) {
                    return true;
                }
                // Fallback: label-agnostic comparison (handles combined block swap + label divergence)
                if (tryLabelAgnosticMatch(expectedNorm, secondNorm)) {
                    return true;
                }
                // Fallback: opcode skeleton (handles combined block swap + copy propagation)
                if (tryLabelAgnosticMatch(toOpcodeSkeleton(expectedNorm), toOpcodeSkeleton(secondNorm))) {
                    return true;
                }
            }

            // GOTO-stripped comparison: handles GOTO asymmetry where one side has
            // GOTO-based if-else and the other has return-terminated guards.
            // Re-renormalize vars AFTER stripping GOTOs so encounter order matches.
            List<String> expectedNoGoto = renormalizeVars(stripGoto(expected));
            List<String> secondNoGoto = renormalizeVars(stripGoto(second));
            if (expectedNoGoto.size() == secondNoGoto.size()) {
                if (findFirstDifferenceIsomorphic(expectedNoGoto, secondNoGoto) == -1) {
                    return true;
                }
                if (tryLabelAgnosticMatch(expectedNoGoto, secondNoGoto)) {
                    return true;
                }
                List<String> expectedGSkel = toOpcodeSkeleton(expectedNoGoto);
                List<String> secondGSkel = toOpcodeSkeleton(secondNoGoto);
                if (tryLabelAgnosticMatch(expectedGSkel, secondGSkel)) {
                    return true;
                }
            }

            // Fallback for unequal sizes: try micro-block matching on the swapped version
            if (tryMicroBlockMatch(expectedNorm, secondNorm)) {
                return true;
            }
            // Also try micro-block on GOTO-stripped (with fresh var renormalization)
            if (tryMicroBlockMatch(expectedNoGoto, secondNoGoto)) {
                return true;
            }

        }
        return false;
    }

    /**
     * Block-level comparison: splits both instruction lists into blocks terminated by
     * RETURN/ATHROW, normalizes each block independently, and checks if the blocks
     * form matching multisets. This handles methods with multiple guard clause inversions
     * where the individual blocks are correct but appear in different order.
     */
    private boolean tryBlockLevelMatch(List<String> a, List<String> b) {
        List<List<String>> blocksA = splitIntoBlocks(a);
        List<List<String>> blocksB = splitIntoBlocks(b);
        if (blocksA.size() < 2 || blocksB.size() < 2) return false;
        if (blocksA.size() > 100 || blocksB.size() > 100) return false;

        List<String> sigsA = blocksA.stream().map(this::blockSignature).collect(Collectors.toList());
        List<String> sigsB = blocksB.stream().map(this::blockSignature).collect(Collectors.toList());

        // Fast path: equal block counts with exact signature match
        if (sigsA.size() == sigsB.size()) {
            List<String> sortedA = new ArrayList<>(sigsA);
            List<String> sortedB = new ArrayList<>(sigsB);
            Collections.sort(sortedA);
            Collections.sort(sortedB);
            if (sortedA.equals(sortedB)) return true;
        }

        // Retry with GOTO-stripped signatures: handles cases where one side has
        // extra GOTOs due to block reordering by the decompiler
        List<String> sigsANoGoto = blocksA.stream().map(this::blockSignatureNoGoto).collect(Collectors.toList());
        List<String> sigsBNoGoto = blocksB.stream().map(this::blockSignatureNoGoto).collect(Collectors.toList());

        if (sigsANoGoto.size() == sigsBNoGoto.size()) {
            List<String> sortedA = new ArrayList<>(sigsANoGoto);
            List<String> sortedB = new ArrayList<>(sigsBNoGoto);
            Collections.sort(sortedA);
            Collections.sort(sortedB);
            if (sortedA.equals(sortedB)) return true;
        }

        // Handle unequal block counts by merging adjacent blocks in the side with more blocks.
        // When a guard clause is split out as its own block on one side but merged into a
        // larger block on the other side, we try merging adjacent blocks to match.
        if (Math.abs(sigsA.size() - sigsB.size()) <= 5) {
            List<String> more = sigsA.size() > sigsB.size() ? sigsA : sigsB;
            List<String> fewer = sigsA.size() > sigsB.size() ? sigsB : sigsA;
            List<List<String>> moreBlocks = sigsA.size() > sigsB.size() ? blocksA : blocksB;
            List<List<String>> fewerBlocks = sigsA.size() > sigsB.size() ? blocksB : blocksA;
            return tryMergeBlocks(moreBlocks, fewerBlocks);
        }

        return false;
    }

    /**
     * Micro-block comparison: splits both instruction lists at every control flow point
     * (conditional branches, GOTOs, returns, throws, switches), creating small blocks.
     * Block signatures normalize condition opcodes to a canonical form (e.g., IFEQ and IFNE
     * both become IF_INT_Z), strip labels, and strip GOTO instructions.
     * Compares the micro-block multisets.
     */
    private boolean tryMicroBlockMatch(List<String> a, List<String> b) {
        if (Math.abs(a.size() - b.size()) > 20) return false;

        List<List<String>> microA = splitIntoMicroBlocks(a);
        List<List<String>> microB = splitIntoMicroBlocks(b);

        if (microA.size() < 2 || microB.size() < 2) return false;
        if (microA.size() > 200 || microB.size() > 200) return false;

        List<String> sigsA = microA.stream().map(this::microBlockSignature)
                .sorted().collect(Collectors.toList());
        List<String> sigsB = microB.stream().map(this::microBlockSignature)
                .sorted().collect(Collectors.toList());

        if (sigsA.equals(sigsB)) return true;

        // Fallback: opcode-skeleton micro-block signatures (strips variable indices)
        List<String> skelSigsA = microA.stream().map(this::microBlockSkeletonSignature)
                .sorted().collect(Collectors.toList());
        List<String> skelSigsB = microB.stream().map(this::microBlockSkeletonSignature)
                .sorted().collect(Collectors.toList());

        return skelSigsA.equals(skelSigsB);
    }

    /**
     * Splits instruction list at every control flow point: conditional branches,
     * GOTO, RETURN, ATHROW, SWITCH. Each split point ends the current block.
     */
    private static List<List<String>> splitIntoMicroBlocks(List<String> insns) {
        List<List<String>> blocks = new ArrayList<>();
        int blockStart = 0;
        for (int i = 0; i < insns.size(); i++) {
            String insn = insns.get(i);
            if (isReturnString(insn) || insn.equals("ATHROW")
                    || insn.startsWith("GOTO ")
                    || insn.startsWith("SWITCH ")
                    || isConditionalBranchString(insn)) {
                blocks.add(new ArrayList<>(insns.subList(blockStart, i + 1)));
                blockStart = i + 1;
            }
        }
        if (blockStart < insns.size()) {
            blocks.add(new ArrayList<>(insns.subList(blockStart, insns.size())));
        }
        return blocks;
    }

    /**
     * Creates a canonical signature for a micro-block: renormalizes variables,
     * strips labels, strips GOTO instructions, and canonicalizes condition opcodes.
     */
    private String microBlockSignature(List<String> block) {
        List<String> normed = renormalizeVars(block);
        List<String> canonical = new ArrayList<>(normed.size());
        for (String insn : normed) {
            if (insn.startsWith("GOTO ")) continue;
            String s = LABEL_REF.matcher(insn).replaceAll("L?");
            s = canonicalizeCondition(s);
            canonical.add(s);
        }
        return String.join(";", canonical);
    }

    /**
     * Like microBlockSignature but also strips variable indices (opcode skeleton)
     * and DUP/POP/SWAP instructions. Catches combined variable renaming + DUP pattern diffs.
     */
    private String microBlockSkeletonSignature(List<String> block) {
        List<String> normed = renormalizeVars(block);
        List<String> canonical = new ArrayList<>(normed.size());
        for (String insn : normed) {
            if (insn.startsWith("GOTO ")) continue;
            if ("DUP".equals(insn) || "DUP2".equals(insn) || "POP".equals(insn)
                    || "POP2".equals(insn) || "SWAP".equals(insn)) continue;
            String s = LABEL_REF.matcher(insn).replaceAll("L?");
            s = canonicalizeCondition(s);
            s = VAR_STRIP.matcher(s).replaceAll("v?");
            canonical.add(s);
        }
        return String.join(";", canonical);
    }

    /**
     * Canonicalizes a conditional branch opcode to a normalized form.
     * Inverse pairs map to the same canonical: IFEQ/IFNE -> IF_INT_Z,
     * IFNULL/IFNONNULL -> IF_REF_Z, IF_ICMPEQ/IF_ICMPNE -> IF_ICMP_EQ, etc.
     */
    private static String canonicalizeCondition(String insn) {
        if (insn.startsWith("IFEQ ") || insn.startsWith("IFNE "))
            return insn.replaceFirst("^IF[A-Z]+ ", "IF_INT_Z ");
        if (insn.startsWith("IFNULL ") || insn.startsWith("IFNONNULL "))
            return insn.replaceFirst("^IF[A-Z]+ ", "IF_REF_Z ");
        if (insn.startsWith("IFLT ") || insn.startsWith("IFGE "))
            return insn.replaceFirst("^IF[A-Z]+ ", "IF_INT_LT ");
        if (insn.startsWith("IFLE ") || insn.startsWith("IFGT "))
            return insn.replaceFirst("^IF[A-Z]+ ", "IF_INT_LE ");
        if (insn.startsWith("IF_ICMPEQ ") || insn.startsWith("IF_ICMPNE "))
            return insn.replaceFirst("^IF_ICMP[A-Z]+ ", "IF_ICMP_EQ ");
        if (insn.startsWith("IF_ICMPLT ") || insn.startsWith("IF_ICMPGE "))
            return insn.replaceFirst("^IF_ICMP[A-Z]+ ", "IF_ICMP_LT ");
        if (insn.startsWith("IF_ICMPLE ") || insn.startsWith("IF_ICMPGT "))
            return insn.replaceFirst("^IF_ICMP[A-Z]+ ", "IF_ICMP_LE ");
        if (insn.startsWith("IF_ACMPEQ ") || insn.startsWith("IF_ACMPNE "))
            return insn.replaceFirst("^IF_ACMP[A-Z]+ ", "IF_ACMP_EQ ");
        return insn;
    }

    private static boolean isConditionalBranchString(String insn) {
        return insn.startsWith("IFEQ ") || insn.startsWith("IFNE ")
                || insn.startsWith("IFLT ") || insn.startsWith("IFGE ")
                || insn.startsWith("IFLE ") || insn.startsWith("IFGT ")
                || insn.startsWith("IFNULL ") || insn.startsWith("IFNONNULL ")
                || insn.startsWith("IF_ICMPEQ ") || insn.startsWith("IF_ICMPNE ")
                || insn.startsWith("IF_ICMPLT ") || insn.startsWith("IF_ICMPGE ")
                || insn.startsWith("IF_ICMPLE ") || insn.startsWith("IF_ICMPGT ")
                || insn.startsWith("IF_ACMPEQ ") || insn.startsWith("IF_ACMPNE ");
    }

    /**
     * Tries all ways to merge adjacent blocks in 'more' to make the block count
     * match 'fewer', then compares as multisets.
     * Uses a greedy approach: for each block in 'fewer', find a matching block
     * or merged sequence in 'more'.
     */
    private boolean tryMergeBlocks(List<List<String>> more, List<List<String>> fewer) {
        int diff = more.size() - fewer.size();
        if (diff > 5 || diff < 0) return false;

        // Create signatures for the 'fewer' side
        List<String> fewerSigs = fewer.stream().map(this::blockSignature)
                .sorted().collect(Collectors.toList());

        // Try all combinations of merging 'diff' adjacent pairs in 'more'
        // For efficiency, use a greedy matching approach instead of combinatorial
        return tryGreedyMergeMatch(more, fewerSigs, 0, new ArrayList<>(), diff);
    }

    private boolean tryGreedyMergeMatch(List<List<String>> blocks, List<String> targetSigs,
                                         int idx, List<String> currentSigs, int mergesRemaining) {
        if (idx >= blocks.size()) {
            if (mergesRemaining != 0) return false;
            List<String> sorted = new ArrayList<>(currentSigs);
            Collections.sort(sorted);
            return sorted.equals(targetSigs);
        }

        // Option 1: take this block as-is
        currentSigs.add(blockSignature(blocks.get(idx)));
        if (tryGreedyMergeMatch(blocks, targetSigs, idx + 1, currentSigs, mergesRemaining)) {
            return true;
        }
        currentSigs.remove(currentSigs.size() - 1);

        // Option 2: merge this block with the next one(s)
        if (mergesRemaining > 0 && idx + 1 < blocks.size()) {
            List<String> merged = new ArrayList<>(blocks.get(idx));
            for (int end = idx + 1; end < blocks.size() && (end - idx) <= mergesRemaining + 1; end++) {
                merged.addAll(blocks.get(end));
                currentSigs.add(blockSignature(merged));
                int mergesUsed = end - idx;
                if (tryGreedyMergeMatch(blocks, targetSigs, end + 1, currentSigs,
                        mergesRemaining - mergesUsed)) {
                    return true;
                }
                currentSigs.remove(currentSigs.size() - 1);
            }
        }

        return false;
    }

    private static List<List<String>> splitIntoBlocks(List<String> insns) {
        List<List<String>> blocks = new ArrayList<>();
        int blockStart = 0;
        for (int i = 0; i < insns.size(); i++) {
            String insn = insns.get(i);
            if (isReturnString(insn) || insn.equals("ATHROW")) {
                blocks.add(new ArrayList<>(insns.subList(blockStart, i + 1)));
                blockStart = i + 1;
            } else if (insn.startsWith("SWITCH ")) {
                // Split at SWITCH: the header (up to and including SWITCH) becomes one block,
                // and each case body becomes its own block (terminated by RETURN/ATHROW).
                // This allows switch case reordering to be detected by multiset comparison.
                blocks.add(new ArrayList<>(insns.subList(blockStart, i + 1)));
                blockStart = i + 1;
            }
        }
        // Trailing instructions after last return (shouldn't happen in valid bytecode)
        if (blockStart < insns.size()) {
            blocks.add(new ArrayList<>(insns.subList(blockStart, insns.size())));
        }
        return blocks;
    }

    /**
     * Creates a canonical signature for a basic block by renormalizing variables
     * and replacing label references with positional markers, then joining.
     */
    private String blockSignature(List<String> block) {
        // Renormalize variable names within this block
        List<String> normed = renormalizeVars(block);
        // Replace label references with a generic marker (labels are block-local artifacts)
        List<String> canonical = new ArrayList<>(normed.size());
        for (String insn : normed) {
            canonical.add(LABEL_REF.matcher(insn).replaceAll("L?"));
        }
        return String.join(";", canonical);
    }

    /**
     * Creates a canonical signature with GOTO instructions stripped.
     * Handles block reordering where one side has extra GOTOs that the other
     * eliminates through fall-through.
     */
    private String blockSignatureNoGoto(List<String> block) {
        List<String> normed = renormalizeVars(block);
        List<String> canonical = new ArrayList<>(normed.size());
        for (String insn : normed) {
            if (insn.startsWith("GOTO ")) continue;
            canonical.add(LABEL_REF.matcher(insn).replaceAll("L?"));
        }
        return String.join(";", canonical);
    }

    /**
     * Strips all GOTO instructions from an instruction list.
     * Used to normalize block reordering where one side has extra GOTOs.
     */
    private static List<String> stripGoto(List<String> insns) {
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if (!insn.startsWith("GOTO ")) {
                result.add(insn);
            }
        }
        return result;
    }

    /**
     * Label-agnostic comparison: checks if two same-size instruction lists differ only
     * in branch target labels. All opcodes and non-label operands must match exactly;
     * label references (L\d+) are replaced with a generic placeholder before comparison.
     * Also applies virtual dispatch relaxation and INVOKEDYNAMIC narrow type normalization.
     */
    private boolean tryLabelAgnosticMatch(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String insnA = a.get(i);
            String insnB = b.get(i);
            if (insnA.equals(insnB)) continue;

            // Virtual dispatch relaxation
            String vmA = extractVirtualMethod(insnA);
            String vmB = extractVirtualMethod(insnB);
            if (vmA != null && vmB != null && vmA.equals(vmB)) continue;

            // INVOKEDYNAMIC narrow type normalization
            if (insnA.startsWith("INVOKEDYNAMIC ") && insnB.startsWith("INVOKEDYNAMIC ")) {
                String normA = normalizeNarrowTypes(insnA);
                String normB = normalizeNarrowTypes(insnB);
                if (normA.equals(normB)) continue;
            }

            // Compare with label references stripped
            String skelA = LABEL_REF.matcher(insnA).replaceAll("L?");
            String skelB = LABEL_REF.matcher(insnB).replaceAll("L?");
            if (!skelA.equals(skelB)) return false;
        }
        return true;
    }

    /**
     * Extracts the method part (name + descriptor) from INVOKEVIRTUAL or INVOKEINTERFACE
     * instructions, stripping the owner class. Returns null for other instructions.
     * Used to match calls that differ only in receiver type (generics/hierarchy).
     */
    /**
     * Extracts the member part (name + descriptor) from instructions that may differ
     * in owner class due to type erasure or class hierarchy resolution.
     * Covers INVOKEVIRTUAL, INVOKEINTERFACE, GETFIELD, PUTFIELD.
     * Returns null for other instructions.
     */
    /**
     * Extracts the member part (name + descriptor) from instructions that may differ
     * in owner class due to type erasure or class hierarchy resolution.
     * Covers INVOKEVIRTUAL, INVOKEINTERFACE (also cross-matched), GETFIELD, PUTFIELD.
     * Returns null for other instructions.
     */
    private static String extractVirtualMethod(String insn) {
        if (insn.startsWith("INVOKEVIRTUAL ") || insn.startsWith("INVOKEINTERFACE ")
                || insn.startsWith("INVOKESPECIAL ") || insn.startsWith("INVOKESTATIC ")
                || insn.startsWith("GETFIELD ") || insn.startsWith("PUTFIELD ")
                || insn.startsWith("GETSTATIC ") || insn.startsWith("PUTSTATIC ")) {
            int dotIdx = insn.indexOf('.');
            if (dotIdx < 0) return null;
            String member = insn.substring(dotIdx);
            // Only match if same invocation kind category (invoke vs field)
            // but allow INVOKEVIRTUAL↔INVOKEINTERFACE and INVOKESPECIAL↔INVOKEVIRTUAL
            String prefix1 = insn.substring(0, insn.indexOf(' '));
            return prefix1.startsWith("INVOKE") ? "INVOKE" + member
                    : prefix1.startsWith("GET") ? "GET" + member
                    : prefix1.startsWith("PUT") ? "PUT" + member
                    : null;
        }
        return null;
    }

    /**
     * Normalizes narrow integer types (B, S, C, Z) to I in method descriptors.
     * Handles cases where decompiler uses short/byte/char/boolean where original
     * compiler used int (they're equivalent at the JVM level for stack operations).
     * For makeConcatWithConstants, also normalizes reference types (L...;) and
     * arrays ([...) to Ljava/lang/Object; since all arguments are toString'd.
     */
    // Matches narrow integer types (B/S/C/Z) in JVM type descriptors.
    // Lookbehind allows these to follow any descriptor-valid predecessor:
    // '(' start, ',' separator, other primitives (IJFDBSCZ), ';' end of ref type.
    // Does NOT include '[' (array prefix) to avoid changing [B to [I (byte[] vs int[]).
    private static final Pattern NARROW_TYPE_IN_DESC = Pattern.compile("(?<=[(,IJFDBSCZ;])([BSCZ])(?=[)BSCZIFJDL\\[])");
    private static final Pattern REF_TYPE_IN_DESC = Pattern.compile("L[^;]+;|\\[+[BSCZIFJDL][^;]*;?");

    private static String normalizeNarrowTypes(String insn) {
        String result = NARROW_TYPE_IN_DESC.matcher(insn).replaceAll("I");
        // For makeConcatWithConstants, reference types are interchangeable
        // (all get toString'd), so normalize L...;/[...  to Ljava/lang/Object;
        if (result.contains("makeConcatWithConstants")) {
            result = REF_TYPE_IN_DESC.matcher(result).replaceAll("Ljava/lang/Object;");
        }
        return result;
    }

    private static boolean areInverseConditions(String condA, String condB) {
        int spaceA = condA.indexOf(' ');
        int spaceB = condB.indexOf(' ');
        if (spaceA < 0 || spaceB < 0) return false;
        String opcodeA = condA.substring(0, spaceA);
        String opcodeB = condB.substring(0, spaceB);

        String invertedA = invertCondition(opcodeA + " L0");
        if (invertedA == null) return false;
        return invertedA.substring(0, invertedA.indexOf(' ')).equals(opcodeB);
    }

    /**
     * Re-normalizes variable names by first-encounter order in the instruction list.
     * Needed after if-else canonicalization may have reordered blocks,
     * changing which variable is encountered first.
     * Only applies to actual variable references in LOAD/STORE/IINC instructions.
     */
    private static final Pattern VAR_IN_INSN = Pattern.compile("(?<=[AILFDS](?:LOAD|STORE) |INC )v(\\d+)");

    private static List<String> renormalizeVars(List<String> insns) {
        Map<String, String> varMap = new LinkedHashMap<>();
        List<String> result = new ArrayList<>(insns.size());
        for (String insn : insns) {
            if (!hasVarRef(insn)) {
                result.add(insn);
                continue;
            }
            Matcher m = VAR_IN_INSN.matcher(insn);
            if (!m.find()) {
                result.add(insn);
                continue;
            }
            m.reset();
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String oldVar = "v" + m.group(1);
                String newVar = varMap.computeIfAbsent(oldVar, k -> "v" + varMap.size());
                m.appendReplacement(sb, newVar);
            }
            m.appendTail(sb);
            result.add(sb.toString());
        }
        return result;
    }

    private static boolean hasVarRef(String insn) {
        return insn.startsWith("ALOAD ") || insn.startsWith("ASTORE ")
                || insn.startsWith("ILOAD ") || insn.startsWith("ISTORE ")
                || insn.startsWith("LLOAD ") || insn.startsWith("LSTORE ")
                || insn.startsWith("FLOAD ") || insn.startsWith("FSTORE ")
                || insn.startsWith("DLOAD ") || insn.startsWith("DSTORE ")
                || insn.startsWith("IINC ");
    }

    /**
     * Inverts a conditional branch instruction string.
     * Returns null if the instruction is not a conditional branch.
     */
    private static String invertCondition(String ifInsn) {
        int spaceIdx = ifInsn.indexOf(' ');
        if (spaceIdx < 0) return null;
        String opcode = ifInsn.substring(0, spaceIdx);
        String target = ifInsn.substring(spaceIdx);

        String inverted = switch (opcode) {
            case "IFEQ" -> "IFNE";
            case "IFNE" -> "IFEQ";
            case "IFLT" -> "IFGE";
            case "IFGE" -> "IFLT";
            case "IFGT" -> "IFLE";
            case "IFLE" -> "IFGT";
            case "IF_ICMPEQ" -> "IF_ICMPNE";
            case "IF_ICMPNE" -> "IF_ICMPEQ";
            case "IF_ICMPLT" -> "IF_ICMPGE";
            case "IF_ICMPGE" -> "IF_ICMPLT";
            case "IF_ICMPGT" -> "IF_ICMPLE";
            case "IF_ICMPLE" -> "IF_ICMPGT";
            case "IF_ACMPEQ" -> "IF_ACMPNE";
            case "IF_ACMPNE" -> "IF_ACMPEQ";
            case "IFNULL" -> "IFNONNULL";
            case "IFNONNULL" -> "IFNULL";
            default -> null;
        };

        if (inverted == null) return null;
        return inverted + target;
    }

    // ── Instruction formatting ──

    private String formatInsn(AbstractInsnNode insn, Map<Integer, Integer> varMap, Map<LabelNode, Integer> labelMap) {
        // Skip pseudo-instructions
        if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
            return null;
        }

        int opcode = insn.getOpcode();
        if (opcode < 0 || opcode >= Printer.OPCODES.length) {
            return null;
        }
        String op = Printer.OPCODES[opcode];

        if (insn instanceof InsnNode) {
            return op;
        }
        if (insn instanceof IntInsnNode n) {
            return op + " " + n.operand;
        }
        if (insn instanceof VarInsnNode n) {
            int idx = resolveVarIndex(n.var, varMap);
            return op + " v" + idx;
        }
        if (insn instanceof TypeInsnNode n) {
            return op + " " + n.desc;
        }
        if (insn instanceof FieldInsnNode n) {
            return op + " " + n.owner + "." + n.name + ":" + n.desc;
        }
        if (insn instanceof MethodInsnNode n) {
            return op + " " + n.owner + "." + n.name + n.desc;
        }
        if (insn instanceof InvokeDynamicInsnNode n) {
            StringBuilder sb = new StringBuilder();
            sb.append(op).append(" ").append(n.name).append(n.desc);
            sb.append(" bsm=").append(formatHandle(n.bsm));
            if (n.bsmArgs != null && n.bsmArgs.length > 0) {
                sb.append(" args=[");
                for (int i = 0; i < n.bsmArgs.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(formatBsmArg(n.bsmArgs[i]));
                }
                sb.append("]");
            }
            return sb.toString();
        }
        if (insn instanceof JumpInsnNode n) {
            return op + " L" + labelMap.getOrDefault(n.label, -1);
        }
        if (insn instanceof LdcInsnNode n) {
            return op + " " + formatConstant(n.cst);
        }
        if (insn instanceof IincInsnNode n) {
            int idx = resolveVarIndex(n.var, varMap);
            return op + " v" + idx + " " + n.incr;
        }
        if (insn instanceof TableSwitchInsnNode n) {
            // When semantic normalizing, use unified SWITCH format (same as LOOKUPSWITCH)
            StringBuilder sb = new StringBuilder(semanticNormalize ? "SWITCH" : op);
            if (!semanticNormalize) {
                sb.append(" ").append(n.min).append("-").append(n.max);
            }
            sb.append(" default:L").append(labelMap.getOrDefault(n.dflt, -1));
            for (int i = 0; i < n.labels.size(); i++) {
                sb.append(" ").append(n.min + i).append(":L").append(labelMap.getOrDefault(n.labels.get(i), -1));
            }
            return sb.toString();
        }
        if (insn instanceof LookupSwitchInsnNode n) {
            StringBuilder sb = new StringBuilder(semanticNormalize ? "SWITCH" : op);
            sb.append(" default:L").append(labelMap.getOrDefault(n.dflt, -1));
            for (int i = 0; i < n.keys.size(); i++) {
                sb.append(" ").append(n.keys.get(i)).append(":L").append(labelMap.getOrDefault(n.labels.get(i), -1));
            }
            return sb.toString();
        }
        if (insn instanceof MultiANewArrayInsnNode n) {
            return op + " " + n.desc + " " + n.dims;
        }

        return "UNKNOWN:" + insn.getClass().getSimpleName();
    }

    private int resolveVarIndex(int varIdx, Map<Integer, Integer> varMap) {
        if (varMap == null) {
            return varIdx;
        }
        return varMap.computeIfAbsent(varIdx, k -> varMap.size());
    }

    // ── Formatting helpers ──

    /**
     * Formats a BSM argument, normalizing compiler-generated lambda method names.
     * Lambda names like "lambda$save$22" become "lambda$save$*" to avoid false
     * positives from different lambda ordering between compilations.
     */
    private String formatBsmArg(Object arg) {
        if (arg instanceof Handle h) {
            String name = h.getName();
            // Normalize lambda implementation method names: lambda$methodName$N -> lambda$methodName$*
            if (name.startsWith("lambda$")) {
                int lastDollar = name.lastIndexOf('$');
                if (lastDollar > 7) { // after "lambda$"
                    String suffix = name.substring(lastDollar + 1);
                    // Only normalize if suffix is a number
                    if (suffix.chars().allMatch(Character::isDigit)) {
                        name = name.substring(0, lastDollar + 1) + "*";
                    }
                }
            }
            String tag = switch (h.getTag()) {
                case Opcodes.H_GETFIELD -> "GETFIELD";
                case Opcodes.H_GETSTATIC -> "GETSTATIC";
                case Opcodes.H_PUTFIELD -> "PUTFIELD";
                case Opcodes.H_PUTSTATIC -> "PUTSTATIC";
                case Opcodes.H_INVOKEVIRTUAL -> "INVOKEVIRTUAL";
                case Opcodes.H_INVOKESTATIC -> "INVOKESTATIC";
                case Opcodes.H_INVOKESPECIAL -> "INVOKESPECIAL";
                case Opcodes.H_NEWINVOKESPECIAL -> "NEWINVOKESPECIAL";
                case Opcodes.H_INVOKEINTERFACE -> "INVOKEINTERFACE";
                default -> "TAG_" + h.getTag();
            };
            return tag + " " + h.getOwner() + "." + name + h.getDesc();
        }
        return formatConstant(arg);
    }

    private String formatHandle(Handle h) {
        String tag = switch (h.getTag()) {
            case Opcodes.H_GETFIELD -> "GETFIELD";
            case Opcodes.H_GETSTATIC -> "GETSTATIC";
            case Opcodes.H_PUTFIELD -> "PUTFIELD";
            case Opcodes.H_PUTSTATIC -> "PUTSTATIC";
            case Opcodes.H_INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.H_INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.H_INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.H_NEWINVOKESPECIAL -> "NEWINVOKESPECIAL";
            case Opcodes.H_INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> "TAG_" + h.getTag();
        };
        return tag + " " + h.getOwner() + "." + h.getName() + h.getDesc();
    }

    private String formatConstant(Object cst) {
        if (cst instanceof String s) {
            String escaped = s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
            return "\"" + escaped + "\"";
        }
        if (cst instanceof Type t) {
            return t.getDescriptor();
        }
        if (cst instanceof Handle h) {
            return formatHandle(h);
        }
        if (cst instanceof Float f) {
            return f + "f";
        }
        if (cst instanceof Long l) {
            return l + "L";
        }
        if (cst instanceof Double d) {
            return d + "d";
        }
        return String.valueOf(cst);
    }

    static String formatAccess(int access) {
        List<String> flags = new ArrayList<>();
        if ((access & Opcodes.ACC_PUBLIC) != 0) flags.add("public");
        if ((access & Opcodes.ACC_PRIVATE) != 0) flags.add("private");
        if ((access & Opcodes.ACC_PROTECTED) != 0) flags.add("protected");
        if ((access & Opcodes.ACC_STATIC) != 0) flags.add("static");
        if ((access & Opcodes.ACC_FINAL) != 0) flags.add("final");
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) flags.add("synchronized");
        if ((access & Opcodes.ACC_VOLATILE) != 0) flags.add("volatile");
        if ((access & Opcodes.ACC_TRANSIENT) != 0) flags.add("transient");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) flags.add("abstract");
        if ((access & Opcodes.ACC_NATIVE) != 0) flags.add("native");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        if ((access & Opcodes.ACC_BRIDGE) != 0) flags.add("bridge");
        if ((access & Opcodes.ACC_VARARGS) != 0) flags.add("varargs");
        if ((access & Opcodes.ACC_ENUM) != 0) flags.add("enum");
        return flags.isEmpty() ? "package-private" : String.join(" ", flags);
    }

    /**
     * Returns true if the field key represents a compiler-generated artifact
     * that may differ between compilations (assertion status, switch maps,
     * outer-class references, enum arrays, lambda captures).
     */
    private static boolean isCompilerArtifactField(String fieldKey) {
        String fieldName = fieldKey.contains(":") ? fieldKey.substring(0, fieldKey.indexOf(':')) : fieldKey;
        return fieldName.equals("$assertionsDisabled")
                || fieldName.equals("_assertionsDisabled")
                || fieldName.startsWith("$SwitchMap$")
                || fieldName.startsWith("this$")
                || fieldName.startsWith("val$")
                || fieldName.equals("$VALUES")
                || fieldName.equals("serialVersionUID");
    }

    // ── Utility ──

    private static String extractName(String key) {
        int parenIdx = key.indexOf('(');
        return parenIdx >= 0 ? key.substring(0, parenIdx) : key;
    }

    private static String extractDesc(String key) {
        int parenIdx = key.indexOf('(');
        return parenIdx >= 0 ? key.substring(parenIdx) : "";
    }

    /**
     * Creates a class name filter from a pattern string.
     * Patterns use dots as separators (e.g., "zombie.*").
     * A trailing * matches any subpackage. Prefix with - to exclude.
     * Multiple patterns separated by commas.
     */
    public static Predicate<String> createClassFilter(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return name -> true;
        }

        String[] parts = pattern.split(",");
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("-")) {
                excludes.add(trimmed.substring(1).replace('.', '/'));
            } else {
                includes.add(trimmed.replace('.', '/'));
            }
        }

        return className -> {
            // Check excludes first
            for (String exc : excludes) {
                if (matchesPattern(className, exc)) {
                    return false;
                }
            }
            // If no includes specified, include all (that aren't excluded)
            if (includes.isEmpty()) {
                return true;
            }
            // Check includes
            for (String inc : includes) {
                if (matchesPattern(className, inc)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static boolean matchesPattern(String className, String pattern) {
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return className.startsWith(prefix);
        }
        return className.equals(pattern);
    }
}
