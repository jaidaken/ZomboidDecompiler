package com.github.zomboiddecompiler;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-decompilation source transforms that fix known Vineflower bugs.
 * Applied to each .java file's content string before writing to disk.
 * Ported from postprocess.py and compile-fix.py.
 */
public final class PostDecompileTransforms {

    private PostDecompileTransforms() {}

    /** Build version constants. */
    public static final String BUILD_41 = "b41";
    public static final String BUILD_42 = "b42";

    /**
     * Apply all transforms to the given Java source content.
     * @param content the decompiled source
     * @param buildVersion build version (e.g. "b41", "b42") for version-specific fixes, or null for all
     */
    public static String apply(String content, String buildVersion) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Shared transforms (apply to all versions)
        content = fixInstanceofPatternScope(content);
        content = fixAssertionsDisabled(content);
        content = fixAssertKeywordToExplicit(content);
        content = fixDuplicateInstanceofPatternVars(content);
        content = fixByteCounterVars(content);
        content = fixBooleanCanonicalization(content);
        content = fixRawCollectionTypes(content);
        content = fixLoggerNullAmbiguity(content);
        content = fixRawgetAsBoolean(content);
        content = fixIntBooleanConfusion(content);
        content = fixMakeConcatWithConstants(content);
        content = fixVariableShadowsClassName(content);
        content = fixRawLambdaAndMethodRef(content);
        content = fixObjectToStringCast(content);
        content = fixRawSortComparators(content);
        content = fixDialogButtonAmbiguity(content);
        content = fixRawsetAmbiguity(content);
        content = fixAnnotationTypeCasts(content);
        content = fixDirectoryStreamForEach(content);
        content = fixSwitchOnObject(content);
        content = fixMistypedJavaIoFile(content);
        content = fixMistypedStringVar(content);
        content = fixObjectToVar(content);
        content = fixRawStreamPath(content);
        content = fixRawLambdaTypeInference(content);
        content = fixStringAssignmentNeedsCast(content);
        content = fixBooleanIntConversion(content);
        content = fixExternalShadowedFieldRefs(content);
        content = fixEmptySwitchExpressionCase(content);
        content = fixUncaughtExceptionInTry(content);
        content = fixRawToArrayCast(content);
        content = fixRawForEachCast(content);
        content = fixRawMethodReturnCast(content);
        content = fixGenericClassInternals(content);
        content = fixWrongStringCastOnGet(content);
        content = fixTableNameNullGuardPattern(content);
        content = fixSandboxFromToTable(content);
        content = fixShortBufferPutMissingCast(content);
        content = addBinaryCompatWarnings(content);

        // Version-specific transforms
        if (!BUILD_42.equals(buildVersion)) {
            // b41-specific (or shared legacy fixes that don't apply to b42)
            content = fixSpecificFileErrors(content);
            content = fixMissingLuaManagerComparator(content);
            content = fixMissingCharacterSoundEmitterSwitchMap(content);
            content = fixAnimStateMissingLambda(content);
            content = fixRenderThreadLambdaOrder(content);
            content = fixPolygonalMap2FindPath(content);
            content = fixItemContainerTryFinallyReturn(content);
            content = fixZomboidHashMapEntryKeyReread(content);
            content = fixIsoFireRandNextFolding(content);
            content = fixUIServerToolboxFloatCast(content);
            content = fixMPStatisticRawsetOverload(content);
            content = fixClimbStateFloatIncrement(content);
            content = fixActionContextTransitionOutCheck(content);
            content = fixVehicleStorySpawnerAngle(content);
        }
        if (BUILD_42.equals(buildVersion)) {
            content = fixB42SpecificErrors(content);
        }

        return content;
    }

    /**
     * Apply all transforms with no build version (backwards compatible).
     */
    public static String apply(String content) {
        return apply(content, null);
    }

    // ========================================================================
    // Fix 1: instanceof pattern variable scope escape
    // ========================================================================

    private static String fixInstanceofPatternScope(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < lines.length) {
            // Pattern A: if (!(expr instanceof Type patVar)) { patVar = fallback; }
            // where patVar is used after the if block
            Matcher m = Pattern.compile(
                    "^(\\s*)if \\(!\\(([\\w.]+) instanceof (\\w+) (\\w+)\\)\\) \\{\\s*$"
            ).matcher(lines[i]);

            if (m.matches() && i + 2 < lines.length) {
                String indent = m.group(1);
                String src = m.group(2);
                String typ = m.group(3);
                String tgt = m.group(4);

                String nextLine = lines[i + 1].trim();
                // Accept any assignment to tgt (not just new Type(...))
                if (nextLine.startsWith(tgt + " = ") && nextLine.endsWith(";")) {
                    int eqIdx = nextLine.indexOf('=');
                    String fallbackExpr = nextLine.substring(eqIdx + 2, nextLine.length() - 1);

                    if (i + 2 < lines.length && lines[i + 2].trim().equals("}")) {
                        result.append(indent).append(typ).append(' ').append(tgt).append(";\n");
                        result.append(indent).append("if (").append(src).append(" instanceof ").append(typ).append(") {\n");
                        result.append(indent).append("   ").append(tgt).append(" = (").append(typ).append(")").append(src).append(";\n");
                        result.append(indent).append("} else {\n");
                        result.append(indent).append("   ").append(tgt).append(" = ").append(fallbackExpr).append(";\n");
                        result.append(indent).append("}");
                        i += 3;
                        if (i < lines.length) result.append('\n');
                        continue;
                    }
                }
            }

            // Pattern B: if (!(expr instanceof Type patVar)) { multi-line-body }
            // where patVar is used AFTER the if block (not just inside)
            Matcher m2 = Pattern.compile(
                    "^(\\s*)if \\(!\\(([\\w.]+) instanceof (\\w+) (\\w+)\\)\\) \\{\\s*$"
            ).matcher(lines[i]);
            if (m2.matches()) {
                String indent = m2.group(1);
                String src = m2.group(2);
                String typ = m2.group(3);
                String tgt = m2.group(4);
                // Find the closing brace of this if block
                int braceDepth = 1;
                int closingBrace = -1;
                for (int j = i + 1; j < lines.length && braceDepth > 0; j++) {
                    braceDepth += countChar(lines[j], '{') - countChar(lines[j], '}');
                    if (braceDepth == 0) { closingBrace = j; break; }
                }
                if (closingBrace > 0) {
                    // Check if patVar is used after the closing brace
                    boolean usedAfter = false;
                    int afterEnd = Math.min(closingBrace + 50, lines.length);
                    for (int j = closingBrace + 1; j < afterEnd; j++) {
                        if (Pattern.compile("\\b" + Pattern.quote(tgt) + "\\b").matcher(lines[j]).find()) {
                            usedAfter = true;
                            break;
                        }
                        if (lines[j].trim().equals("}")) break; // end of enclosing scope
                    }
                    if (usedAfter) {
                        // Hoist: declare patVar before if, remove pattern from instanceof
                        result.append(indent).append(typ).append(' ').append(tgt).append(";\n");
                        result.append(indent).append("if (").append(src).append(" instanceof ").append(typ).append(") {\n");
                        result.append(indent).append("   ").append(tgt).append(" = (").append(typ).append(")").append(src).append(";\n");
                        result.append(indent).append("} else {\n");
                        // Copy original if body
                        for (int j = i + 1; j <= closingBrace; j++) {
                            result.append(lines[j]).append('\n');
                        }
                        i = closingBrace + 1;
                        continue;
                    }
                }
            }

            // Pattern C: if (expr instanceof Type patVar) { multi-line-body }
            // where patVar is used AFTER the if block
            Matcher m3 = Pattern.compile(
                    "^(\\s*)if \\(([\\w.]+) instanceof (\\w+) (\\w+)\\) \\{\\s*$"
            ).matcher(lines[i]);
            if (m3.matches()) {
                String indent = m3.group(1);
                String src = m3.group(2);
                String typ = m3.group(3);
                String tgt = m3.group(4);
                int braceDepth = 1;
                int closingBrace = -1;
                for (int j = i + 1; j < lines.length && braceDepth > 0; j++) {
                    braceDepth += countChar(lines[j], '{') - countChar(lines[j], '}');
                    if (braceDepth == 0) { closingBrace = j; break; }
                }
                if (closingBrace > 0) {
                    boolean usedAfter = false;
                    int afterEnd = Math.min(closingBrace + 50, lines.length);
                    for (int j = closingBrace + 1; j < afterEnd; j++) {
                        if (Pattern.compile("\\b" + Pattern.quote(tgt) + "\\b").matcher(lines[j]).find()) {
                            usedAfter = true;
                            break;
                        }
                        if (lines[j].trim().equals("}")) break;
                    }
                    if (usedAfter) {
                        // Hoist declaration, add cast at start of if body
                        result.append(indent).append(typ).append(' ').append(tgt).append(" = null;\n");
                        result.append(indent).append("if (").append(src).append(" instanceof ").append(typ).append(") {\n");
                        // Find indent of first line in body
                        String bodyIndent = indent + "   ";
                        if (i + 1 < lines.length) {
                            Matcher indM = Pattern.compile("^(\\s*)").matcher(lines[i + 1]);
                            if (indM.find()) bodyIndent = indM.group(1);
                        }
                        result.append(bodyIndent).append(tgt).append(" = (").append(typ).append(")").append(src).append(";\n");
                        // Copy original if body
                        for (int j = i + 1; j <= closingBrace; j++) {
                            result.append(lines[j]).append('\n');
                        }
                        i = closingBrace + 1;
                        continue;
                    }
                }
            }

            result.append(lines[i]);
            i++;
            if (i < lines.length) result.append('\n');
        }

        return result.toString();
    }

    // ========================================================================
    // Fix 2: Missing $assertionsDisabled field declaration
    // ========================================================================

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "(?:public |private |protected )?(?:abstract |final )?class (\\w+)[^{]*\\{"
    );

    private static String fixAssertionsDisabled(String content) {
        if (!content.contains("$assertionsDisabled")) return content;

        // If the field is already declared by the decompiler, rename it to avoid
        // conflict with the compiler-synthesized $assertionsDisabled in Java 21+
        if (content.contains("static final boolean $assertionsDisabled")) {
            content = content.replace("$assertionsDisabled", "_assertionsDisabled");
            return content;
        }

        // Otherwise, add the field declaration (and rename to avoid conflicts)
        Matcher m = CLASS_PATTERN.matcher(content);
        if (!m.find()) return content;

        String className = m.group(1);
        int insertPos = m.end();
        String field = "\n   static final boolean _assertionsDisabled = !" +
                className + ".class.desiredAssertionStatus();\n";
        content = content.substring(0, insertPos) + field + content.substring(insertPos);
        content = content.replace("$assertionsDisabled", "_assertionsDisabled");
        return content;
    }

    // ========================================================================
    // Fix 2b: Convert assert keyword to explicit _assertionsDisabled checks
    // ========================================================================
    // When a file has both an explicit _assertionsDisabled field AND assert
    // keywords, javac synthesizes a SECOND $assertionsDisabled field for the
    // assert statements. This causes duplicate initialization in <clinit>.
    // Fix: convert "assert X;" to explicit "if (!_assertionsDisabled && !(X))
    // { throw new AssertionError(); }" so javac doesn't synthesize the duplicate.

    private static final Pattern ASSERT_STMT = Pattern.compile(
            "^(\\s*)assert (.+);\\s*$"
    );

    private static String fixAssertKeywordToExplicit(String content) {
        // Only applies to files that have an explicit _assertionsDisabled field
        // AND assert statements (otherwise no duplication)
        if (!content.contains("_assertionsDisabled")) return content;
        if (!content.contains("\nassert ") && !content.contains(" assert ")) return content;

        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = ASSERT_STMT.matcher(lines[i]);
            if (m.matches()) {
                String indent = m.group(1);
                String condition = m.group(2);
                lines[i] = indent + "if (!_assertionsDisabled && !(" + condition + ")) { throw new AssertionError(); }";
                modified = true;
            }
        }

        return modified ? String.join("\n", lines) : content;
    }

    // ========================================================================
    // Fix 3: Duplicate instanceof pattern variables (self-shadowing)
    // ========================================================================

    private static final Pattern DUP_INSTANCEOF_PATTERN = Pattern.compile(
            "(\\w+)((?:\\.\\w+\\([^)]*\\))+) instanceof (\\w+) \\1\\)"
    );

    private static String fixDuplicateInstanceofPatternVars(String content) {
        if (!DUP_INSTANCEOF_PATTERN.matcher(content).find()) return content;

        String[] lines = content.split("\n", -1);
        List<String> newLines = new ArrayList<>();
        int i = 0;

        while (i < lines.length) {
            String line = lines[i];
            Matcher m = DUP_INSTANCEOF_PATTERN.matcher(line);
            if (m.find()) {
                String varName = m.group(1);
                String methodChain = m.group(2);
                String typeName = m.group(3);

                String newLine = line.replace(
                        "instanceof " + typeName + " " + varName + ")",
                        "instanceof " + typeName + ")"
                );
                newLines.add(newLine);

                if (newLine.trim().endsWith("{")) {
                    String nextIndent;
                    if (i + 1 < lines.length) {
                        Matcher indentM = Pattern.compile("^(\\s*)").matcher(lines[i + 1]);
                        nextIndent = indentM.find() ? indentM.group(1) : "            ";
                    } else {
                        nextIndent = "            ";
                    }
                    newLines.add(nextIndent + typeName + " _" + varName +
                            " = (" + typeName + ")" + varName + methodChain + ";");

                    Pattern varPat = Pattern.compile("\\b" + Pattern.quote(varName) + "\\b");
                    int braceDepth = 1;
                    i++;
                    while (i < lines.length && braceDepth > 0) {
                        String innerLine = lines[i];
                        braceDepth += countChar(innerLine, '{') - countChar(innerLine, '}');
                        if (braceDepth > 0) {
                            innerLine = varPat.matcher(innerLine).replaceAll("_" + varName);
                        }
                        newLines.add(innerLine);
                        i++;
                    }
                    continue;
                }
            } else {
                newLines.add(line);
            }
            i++;
        }

        return String.join("\n", newLines);
    }

    // ========================================================================
    // Fix 4: Byte counter variables widened to int
    // ========================================================================

    private static final Pattern FOR_BYTE_PATTERN = Pattern.compile(
            "for \\(byte (byte\\d+) = (\\d+);"
    );
    private static final Pattern FOR_BOUND_PATTERN = Pattern.compile(
            "for \\(byte (byte\\d+) = (\\d+);\\s*\\1\\s*<\\s*(\\d+)\\s*;"
    );
    private static final Pattern DECL_BYTE_PATTERN = Pattern.compile(
            "^(\\s*)byte (byte\\d+) = (\\d+);$"
    );

    private static String fixByteCounterVars(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = FOR_BYTE_PATTERN.matcher(lines[i]);
            if (!m.find()) continue;
            Matcher boundM = FOR_BOUND_PATTERN.matcher(lines[i]);
            if (boundM.find() && Integer.parseInt(boundM.group(3)) < 128) continue;
            lines[i] = FOR_BYTE_PATTERN.matcher(lines[i]).replaceFirst("for (int $1 = $2;");
            modified = true;
        }

        for (int i = 0; i < lines.length; i++) {
            Matcher m = DECL_BYTE_PATTERN.matcher(lines[i].stripTrailing());
            if (!m.matches()) continue;
            String indent = m.group(1);
            String varName = m.group(2);
            String initVal = m.group(3);
            int scopeEnd = findScopeEnd(lines, i + 1);
            int end = Math.min(i + 200, Math.min(lines.length, scopeEnd));
            if (varNeedsByte(varName, lines, i + 1, end)) continue;
            String vn = Pattern.quote(varName);
            Pattern incrRe = Pattern.compile("\\b" + vn + "\\b\\s*(?:\\+\\+|--|\\+=|-=|=\\s*\\b" + vn + "\\b\\s*[+\\-*/])");
            Pattern indexRe = Pattern.compile("\\[\\s*" + vn + "\\s*[\\]\\+]");
            Pattern loopRe = Pattern.compile("(?:while|for)\\s*\\(.*\\b" + vn + "\\b");
            boolean isCounter = false;
            for (int j = i + 1; j < end; j++) {
                if (incrRe.matcher(lines[j]).find() || indexRe.matcher(lines[j]).find() || loopRe.matcher(lines[j]).find()) {
                    isCounter = true;
                    break;
                }
            }
            if (isCounter) {
                lines[i] = indent + "int " + varName + " = " + initVal + ";";
                modified = true;
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 5: Boolean canonicalization in fromJavaToLua(Boolean)
    // ========================================================================

    private static final Pattern BOOLEAN_CANON_PATTERN = Pattern.compile(
            "(fromJavaToLua\\(Boolean (\\w+)\\)\\s*\\{)\\s*\\n(\\s*)return \\2;"
    );

    private static String fixBooleanCanonicalization(String content) {
        if (!content.contains("fromJavaToLua(Boolean")) return content;
        return BOOLEAN_CANON_PATTERN.matcher(content).replaceAll(
                "$1\n$3return Boolean.valueOf($2.booleanValue());"
        );
    }

    // ========================================================================
    // Fix 6: Raw collection type fixes (for-each, assignment, toArray, operators)
    // ========================================================================

    private static final Set<String> RAW_COLLECTION_TYPES = Set.of(
            "ArrayList", "LinkedList", "Vector", "Stack",
            "HashMap", "LinkedHashMap", "TreeMap", "Hashtable", "ConcurrentHashMap",
            "HashSet", "LinkedHashSet", "TreeSet",
            "List", "Set", "Map", "Collection", "Iterable", "Queue", "Deque",
            "ArrayDeque", "PriorityQueue"
    );

    private static final Pattern RAW_DECL_PATTERN = Pattern.compile(
            "\\b(" + String.join("|", RAW_COLLECTION_TYPES) + ")\\s+(\\w+)\\s*[=;]"
    );

    private static String fixRawCollectionTypes(String content) {
        String[] lines = content.split("\n", -1);

        // Phase 1: collect raw collection variable names
        Set<String> rawVars = new HashSet<>();
        for (String line : lines) {
            Matcher m = RAW_DECL_PATTERN.matcher(line);
            while (m.find()) {
                // Check it's truly raw (no <> before the variable name)
                String prefix = line.substring(0, m.start(2));
                if (!prefix.contains("<")) {
                    rawVars.add(m.group(2));
                }
            }
        }

        if (rawVars.isEmpty()) return content;

        boolean modified = false;

        // Phase 2: Fix for-each loops on raw collections
        // Pattern: for (Type var : rawCollection) or for (Type var : rawCollection.method())
        for (int i = 0; i < lines.length; i++) {
            Matcher fm = Pattern.compile(
                    "^(\\s*)for \\((\\S+(?:<[^>]+>)?) (\\w+) : (\\w+)\\)(.*)$"
            ).matcher(lines[i]);
            if (fm.matches()) {
                String collection = fm.group(4);
                if (rawVars.contains(collection)) {
                    String indent = fm.group(1);
                    String type = fm.group(2);
                    String var = fm.group(3);
                    String rest = fm.group(5);
                    lines[i] = indent + "for (" + type + " " + var + " : (Iterable<" + type + ">)(Iterable<?>)" + collection + ")" + rest;
                    modified = true;
                    continue;
                }
            }

            // for (Type var : rawVar.entrySet()) or similar method call
            fm = Pattern.compile(
                    "^(\\s*)for \\((\\S+(?:<[^>]+>)?) (\\w+) : (\\w+)\\.([\\w.]+\\([^)]*\\))\\)(.*)$"
            ).matcher(lines[i]);
            if (fm.matches()) {
                String collection = fm.group(4);
                if (rawVars.contains(collection)) {
                    String indent = fm.group(1);
                    String type = fm.group(2);
                    String var = fm.group(3);
                    String method = fm.group(5);
                    String rest = fm.group(6);
                    lines[i] = indent + "for (" + type + " " + var + " : (Iterable<" + type + ">)(Iterable<?>)" + collection + "." + method + ")" + rest;
                    modified = true;
                    continue;
                }
            }
        }

        // Phase 3: Fix toArray returning Object[]
        // Pattern: something = rawVar.toArray(new Type[N])
        for (int i = 0; i < lines.length; i++) {
            for (String rawVar : rawVars) {
                String toArrayPat = rawVar + ".toArray(";
                if (!lines[i].contains(toArrayPat)) continue;
                Matcher tm = Pattern.compile(
                        "(" + Pattern.quote(rawVar) + "\\.toArray\\(new (\\w+(?:\\.\\w+)?)\\[\\d*\\]\\))"
                ).matcher(lines[i]);
                if (tm.find()) {
                    String full = tm.group(1);
                    String arrayType = tm.group(2);
                    String cast = "(" + arrayType + "[])" + full;
                    // Don't double-cast
                    int pos = tm.start();
                    if (pos >= 2 && lines[i].substring(pos - 2, pos).contains(")")) continue;
                    lines[i] = lines[i].substring(0, tm.start()) + cast + lines[i].substring(tm.end());
                    modified = true;
                }
            }
        }

        // Phase 4: Fix Type var = rawVar.get/put/remove/putIfAbsent(...) assignments
        for (int i = 0; i < lines.length; i++) {
            Matcher am = Pattern.compile(
                    "^(\\s*)(\\w+(?:<[^>]+>)?) (\\w+) = (\\w+)\\.(get|put|remove|putIfAbsent|getOrDefault)\\((.*)$"
            ).matcher(lines[i]);
            if (am.matches()) {
                String collection = am.group(4);
                if (rawVars.contains(collection)) {
                    String indent = am.group(1);
                    String type = am.group(2);
                    String var = am.group(3);
                    String method = am.group(5);
                    String rest = am.group(6);
                    // Don't cast if type is Object
                    if (!type.equals("Object")) {
                        lines[i] = indent + type + " " + var + " = (" + type + ")" + collection + "." + method + "(" + rest;
                        modified = true;
                    }
                }
            }
        }

        // Phase 5: Fix var += rawMap.get/getOrDefault(...)
        for (int i = 0; i < lines.length; i++) {
            Matcher bm = Pattern.compile(
                    "^(\\s*)(\\w+) (\\+=|-=) (\\w+)\\.(get|getOrDefault)\\((.*)$"
            ).matcher(lines[i]);
            if (bm.matches()) {
                String collection = bm.group(4);
                if (rawVars.contains(collection)) {
                    String indent = bm.group(1);
                    String var = bm.group(2);
                    String op = bm.group(3);
                    String method = bm.group(5);
                    String rest = bm.group(6);
                    lines[i] = indent + var + " " + op + " (int)(Integer)" + collection + "." + method + "(" + rest;
                    modified = true;
                }
            }
        }

        // Phase 6: Fix int + rawMap.getOrDefault(..., 0) — bad operand types
        for (int i = 0; i < lines.length; i++) {
            Matcher pm = Pattern.compile(
                    "(\\d+) \\+ (\\w+)\\.(getOrDefault)\\("
            ).matcher(lines[i]);
            if (pm.find() && rawVars.contains(pm.group(2))) {
                lines[i] = lines[i].substring(0, pm.start()) +
                        pm.group(1) + " + (int)(Integer)" + pm.group(2) + "." + pm.group(3) + "(" +
                        lines[i].substring(pm.end());
                modified = true;
            }
        }

        // Phase 7: Fix hashSet.forEach(var -> consumer.accept(this, var)) on raw sets
        for (int i = 0; i < lines.length; i++) {
            Matcher hm = Pattern.compile(
                    "(\\w+)\\.forEach\\((\\w+) -> (\\w+)\\.accept\\(this, \\2\\)\\)"
            ).matcher(lines[i]);
            if (hm.find() && rawVars.contains(hm.group(1))) {
                // Can't easily infer the type from source alone, skip
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 7: Logger.log null ambiguity
    // ========================================================================

    private static String fixLoggerNullAmbiguity(String content) {
        if (!content.contains(".log(Level.")) return content;
        // .log(Level.X, "string", null) is ambiguous between (String,Object[]) and (String,Throwable)
        return content.replace(
                ".log(Level.SEVERE, ",
                ".log(Level.SEVERE, "
        ).replaceAll(
                "\\.log\\(Level\\.(\\w+), ([^,]+), null\\)",
                ".log(Level.$1, $2, (Throwable)null)"
        );
    }

    // ========================================================================
    // Fix 8: rawget() used as boolean
    // ========================================================================

    private static String fixRawgetAsBoolean(String content) {
        if (!content.contains(".rawget(")) return content;
        // table.rawget("key") ? x : y  →  (Boolean)table.rawget("key") ? x : y
        return content.replaceAll(
                "(\\w+\\.rawget\\([^)]+\\))\\s*\\?",
                "(Boolean)$1 ?"
        );
    }

    // ========================================================================
    // Fix 9: int/boolean confusion
    // ========================================================================

    private static String fixIntBooleanConfusion(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        // Collect variables explicitly declared as boolean or Boolean
        Set<String> boolVars = new HashSet<>();
        // Collect variables explicitly declared as int
        Set<String> intVars = new HashSet<>();
        for (String line : lines) {
            Matcher bm = Pattern.compile("\\b(?:boolean|Boolean) (\\w+)\\s*[=;,)]").matcher(line);
            while (bm.find()) boolVars.add(bm.group(1));
            Matcher im = Pattern.compile("\\bint (\\w+)\\s*[=;,)]").matcher(line);
            while (im.find()) intVars.add(im.group(1));
            // Also boolean parameters
            Matcher pm = Pattern.compile("\\b(?:boolean|Boolean) (\\w+)(?:\\s*[,)])").matcher(line);
            while (pm.find()) boolVars.add(pm.group(1));
        }

        for (int i = 0; i < lines.length; i++) {
            // Fix: boolVar != 0 → boolVar  (boolean compared as int)
            // This is the biggest category (~90 errors)
            // IMPORTANT: must not match != 0.0 (double) and must use word boundaries
            // Skip variables that are ALSO declared as int (name collision across scopes)
            for (String bv : boolVars) {
                if (intVars.contains(bv)) continue; // ambiguous — skip
                String re1 = "\\b" + Pattern.quote(bv) + "\\b != 0(?![.\\d])";
                String re2 = "\\b" + Pattern.quote(bv) + "\\b == 0(?![.\\d])";
                Matcher m1 = Pattern.compile(re1).matcher(lines[i]);
                if (m1.find()) {
                    lines[i] = m1.replaceAll(bv);
                    modified = true;
                }
                Matcher m2 = Pattern.compile(re2).matcher(lines[i]);
                if (m2.find()) {
                    lines[i] = m2.replaceAll(Matcher.quoteReplacement("!" + bv));
                    modified = true;
                }
            }

            // Fix: (boolean)1 → true, (boolean)0 → false
            if (lines[i].contains("(boolean)")) {
                if (lines[i].contains("(boolean)1")) {
                    lines[i] = lines[i].replace("(boolean)1", "true");
                    modified = true;
                }
                if (lines[i].contains("(boolean)0")) {
                    lines[i] = lines[i].replace("(boolean)0", "false");
                    modified = true;
                }
            }

            // Fix: return (boolean)intVar; → return intVar != 0;
            Matcher rm = Pattern.compile("return \\(boolean\\)(\\w+);").matcher(lines[i].trim());
            if (rm.find() && intVars.contains(rm.group(1))) {
                String indent = lines[i].substring(0, lines[i].indexOf("return"));
                lines[i] = indent + "return " + rm.group(1) + " != 0;";
                modified = true;
                continue;
            }

            // Fix: if (intVar) { where intVar is declared as int (not boolean)
            Matcher ifm = Pattern.compile("if \\((\\w+)\\)").matcher(lines[i]);
            if (ifm.find() && intVars.contains(ifm.group(1)) && !boolVars.contains(ifm.group(1))) {
                lines[i] = lines[i].replace("if (" + ifm.group(1) + ")",
                        "if (" + ifm.group(1) + " != 0)");
                modified = true;
            }

            // Fix: if (intVar && boolExpr)
            Matcher andm = Pattern.compile("if \\((\\w+) &&").matcher(lines[i]);
            if (andm.find() && intVars.contains(andm.group(1)) && !boolVars.contains(andm.group(1))) {
                lines[i] = lines[i].replace("if (" + andm.group(1) + " &&",
                        "if (" + andm.group(1) + " != 0 &&");
                modified = true;
            }

            // Fix: if (!intVar) → if (intVar == 0) — C-style int-as-boolean negation
            // IMPORTANT: exclude !var.method() — the dot means it's a method call, not bare negation
            Matcher notm = Pattern.compile("(?<![\\w])!(\\w+)(?![\\w.])").matcher(lines[i]);
            if (notm.find() && intVars.contains(notm.group(1)) && !boolVars.contains(notm.group(1))) {
                lines[i] = lines[i].replaceAll(
                        "(?<![\\w])!" + Pattern.quote(notm.group(1)) + "(?![\\w.])",
                        notm.group(1) + " == 0"
                );
                modified = true;
            }

            // Fix: intVar = 1 != 0; → intVar = 1; (boolean expression assigned to int)
            // IMPORTANT: use word boundaries (\b) to avoid substring matches
            for (String intVar : intVars) {
                if (boolVars.contains(intVar)) continue;
                String ivPat = "\\b" + Pattern.quote(intVar) + "\\b";
                Matcher eq1 = Pattern.compile(ivPat + " = 1 != 0;").matcher(lines[i]);
                if (eq1.find()) {
                    lines[i] = eq1.replaceAll(intVar + " = 1;");
                    modified = true;
                }
                Matcher eq0 = Pattern.compile(ivPat + " = 0 != 0;").matcher(lines[i]);
                if (eq0.find()) {
                    lines[i] = eq0.replaceAll(intVar + " = 0;");
                    modified = true;
                }
                // Fix: intVar = true; → intVar = 1;
                Matcher eqt = Pattern.compile(ivPat + " = true;").matcher(lines[i]);
                if (eqt.find()) {
                    lines[i] = eqt.replaceAll(intVar + " = 1;");
                    modified = true;
                }
                Matcher eqf = Pattern.compile(ivPat + " = false;").matcher(lines[i]);
                if (eqf.find()) {
                    lines[i] = eqf.replaceAll(intVar + " = 0;");
                    modified = true;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 10: makeConcatWithConstants (decompiler artifact)
    // ========================================================================

    private static String fixMakeConcatWithConstants(String content) {
        if (!content.contains("makeConcatWithConstants")) return content;
        // Pattern: StringConcatFactory.makeConcatWithConstants<"makeConcatWithConstants","\u0001">(expr)
        // Replace with: "" + expr
        content = content.replaceAll(
                "StringConcatFactory\\.makeConcatWithConstants<[^>]+>\\(([^)]+)\\)",
                "\"\" + $1"
        );
        return content;
    }

    // ========================================================================
    // Fix 11: Static field shadows class name
    // ========================================================================

    private static final Map<String, Set<String>> KNOWN_INSTANCE_METHODS = Map.of(
            "Thread", Set.of("start", "run", "join", "interrupt", "setName", "getName",
                    "setDaemon", "isDaemon", "setPriority", "getPriority", "isAlive", "getState",
                    "setUncaughtExceptionHandler", "getUncaughtExceptionHandler", "checkAccess"),
            "ArrayList", Set.of("add", "addAll", "remove", "removeAll", "removeIf", "get", "set",
                    "size", "isEmpty", "clear", "contains", "containsAll", "indexOf", "lastIndexOf",
                    "forEach", "toArray", "stream", "sort", "iterator", "listIterator", "subList",
                    "retainAll", "trimToSize", "ensureCapacity")
    );

    private static String fixVariableShadowsClassName(String content) {
        // Find class name
        Matcher cm = Pattern.compile("(?:public |private |protected )?(?:abstract |final )?class (\\w+)").matcher(content);
        if (!cm.find()) return content;
        String className = cm.group(1);

        String[] lines = content.split("\n", -1);
        boolean modified = false;

        // Find static fields that shadow the class name
        for (int i = 0; i < lines.length; i++) {
            Matcher fm = Pattern.compile(
                    "static (?:final )?(\\w+(?:<[^>]+>)?) " + Pattern.quote(className) + "\\s*[=;]"
            ).matcher(lines[i]);
            if (!fm.find()) continue;

            String fieldType = fm.group(1);
            String baseType = fieldType.replaceAll("<.*>", "");

            Set<String> instanceMethods = KNOWN_INSTANCE_METHODS.get(baseType);
            if (instanceMethods == null) continue;

            String newFieldName = "s_" + className.substring(0, 1).toLowerCase() + className.substring(1);

            // Rename the field declaration
            lines[i] = lines[i].replace(fieldType + " " + className, fieldType + " " + newFieldName);
            modified = true;

            // Rename field usages throughout the file using explicit patterns
            String cn = Pattern.quote(className);
            for (int j = 0; j < lines.length; j++) {
                if (j == i) continue;
                if (!lines[j].contains(className)) continue;

                // Pattern 1: ClassName = expr (bare assignment, not ==)
                lines[j] = lines[j].replaceAll(
                        "(?<![.:\\w])" + cn + "(?=\\s*=[^=])",
                        newFieldName
                );

                // Pattern 2: ClassName.instanceMethod(
                for (String method : instanceMethods) {
                    lines[j] = lines[j].replaceAll(
                            "(?<![.:\\w])" + cn + "\\." + Pattern.quote(method) + "(?=\\()",
                            newFieldName + "." + method
                    );
                }

                // Pattern 3: for (... : ClassName)
                lines[j] = lines[j].replaceAll(
                        ":\\s*" + cn + "\\)",
                        ": " + newFieldName + ")"
                );

                // Pattern 4: synchronized (ClassName)
                lines[j] = lines[j].replaceAll(
                        "synchronized \\(" + cn + "\\)",
                        "synchronized (" + newFieldName + ")"
                );

                // Pattern 5: ClassName != or ClassName == (null checks, comparisons)
                lines[j] = lines[j].replaceAll(
                        "(?<![.:\\w])" + cn + "(?=\\s*[!=]=)",
                        newFieldName
                );

                // Pattern 6: != ClassName or == ClassName (right-side of comparison)
                lines[j] = lines[j].replaceAll(
                        "([!=]=\\s*)" + cn + "(?![.:\\w])",
                        "$1" + newFieldName
                );

                // Pattern 7: if (ClassName) — bare in condition (e.g., if (RenderThread != null))
                // Already handled by pattern 5

                // Pattern 8: return ClassName;
                lines[j] = lines[j].replaceAll(
                        "return " + cn + ";",
                        "return " + newFieldName + ";"
                );

                // Pattern 9: (ClassName, or ,ClassName) — method argument
                lines[j] = lines[j].replaceAll(
                        "(?<=[,(]\\s{0,5})" + cn + "(?=\\s*[,)])",
                        newFieldName
                );
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 12: Lambda/method reference on raw types
    // ========================================================================

    private static String fixRawLambdaAndMethodRef(String content) {
        // Fix: rawList.sort(String::compareToIgnoreCase) → rawList.sort((Comparator<String>)(Comparator<?>)String::compareToIgnoreCase)
        // Actually simpler: rawList.sort(String::compareToIgnoreCase) only fails because rawList is raw
        // The fix: cast the list. But we can also use explicit comparator.
        // Simplest: ((List<String>)rawList).sort(String::compareToIgnoreCase)

        // Fix sort with method references
        content = content.replaceAll(
                "(\\w+)\\.sort\\(String::compareToIgnoreCase\\)",
                "((java.util.List<String>)$1).sort(String::compareToIgnoreCase)"
        );
        content = content.replaceAll(
                "(\\w+)\\.sort\\(String::compareTo\\)",
                "((java.util.List<String>)$1).sort(String::compareTo)"
        );

        // Fix: rawList.sort((a, b) -> a.field.compareTo(b.field)) where a,b are Object
        // These need the lambda parameter types. Handled per-pattern below.

        // Fix: rawList.sort(Comparator.comparingInt(x -> x.field))
        // The x is inferred as Object. Need explicit cast.
        // Pattern: Comparator.comparingInt(varName -> varName.field)
        content = content.replaceAll(
                "Comparator\\.comparingInt\\((\\w+) -> \\1\\.(\\w+)\\)",
                "Comparator.comparingInt((java.util.function.ToIntFunction)($1 -> ((Object)$1).$2))"
        );

        // Fix: stream.map(Path::getFileName) on raw stream
        // This needs the stream to be typed
        content = content.replaceAll(
                "(\\w+)\\.map\\(Path::getFileName\\)\\.map\\(Path::toString\\)",
                "((java.util.stream.Stream<java.nio.file.Path>)$1).map(java.nio.file.Path::getFileName).map(java.nio.file.Path::toString)"
        );

        return content;
    }

    // ========================================================================
    // Fix 13: Object variables inferred to correct type from usage
    // ========================================================================

    private static final Set<String> STRING_ONLY_METHODS = Set.of(
            "startsWith", "endsWith", "replaceFirst", "replaceAll",
            "trim", "matches", "substring", "charAt", "split",
            "toUpperCase", "toLowerCase", "concat", "codePointAt",
            "getBytes", "intern", "strip", "stripLeading", "stripTrailing"
    );
    private static final Set<String> FILE_ONLY_METHODS = Set.of(
            "exists", "toPath", "isFile", "isDirectory", "getAbsolutePath",
            "getAbsoluteFile", "mkdirs", "mkdir", "delete", "listFiles",
            "canRead", "canWrite", "getCanonicalPath", "createNewFile"
    );
    private static final Set<String> PATH_ONLY_METHODS = Set.of(
            "getFileName", "toFile", "resolve", "resolveSibling",
            "toAbsolutePath", "normalize", "relativize", "getNameCount"
    );

    private static String fixObjectToStringCast(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();
            // Skip lines that look like method parameters (contain '(' before 'Object')
            if (stripped.contains("(") && stripped.indexOf('(') < stripped.indexOf("Object")) continue;

            // Match: Object varName = expr;
            Matcher m = Pattern.compile("^Object (\\w+) = (.+);$").matcher(stripped);
            if (m.matches()) {
                String varName = m.group(1);
                String init = m.group(2).trim();
                String type = null;

                // Object var = "" or "literal" → String
                if (init.startsWith("\"")) {
                    type = "String";
                }
                // Object var = new Type(...) → rely on scope usage inference instead
                // (direct new Type() inference is too aggressive when vars are reassigned)
                // Fallback: check scope usage
                if (type == null) {
                    int scopeEnd = Math.min(findScopeEnd(lines, i + 1), lines.length);
                    type = inferTypeFromScope(varName, lines, i + 1, scopeEnd);
                }
                if (type != null) {
                    lines[i] = lines[i].replaceFirst(
                            "\\bObject(?= " + Pattern.quote(varName) + " =)", type);
                    modified = true;
                }
                continue;
            }

            // Match: Object varName;
            Matcher m2 = Pattern.compile("^Object (\\w+);$").matcher(stripped);
            if (m2.matches()) {
                String varName = m2.group(1);
                int scopeEnd = Math.min(findScopeEnd(lines, i + 1), lines.length);
                String type = inferTypeFromScope(varName, lines, i + 1, scopeEnd);
                if (type != null) {
                    lines[i] = lines[i].replaceFirst(
                            "\\bObject(?= " + Pattern.quote(varName) + ";)", type);
                    modified = true;
                }
                continue;
            }

            // Match: Object varName = null;
            Matcher m3 = Pattern.compile("^Object (\\w+) = null;$").matcher(stripped);
            if (m3.matches()) {
                String varName = m3.group(1);
                int scopeEnd = Math.min(findScopeEnd(lines, i + 1), lines.length);
                String type = inferTypeFromScope(varName, lines, i + 1, scopeEnd);
                if (type != null) {
                    lines[i] = lines[i].replaceFirst(
                            "\\bObject(?= " + Pattern.quote(varName) + " = null)", type);
                    modified = true;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    private static String inferTypeFromScope(String varName, String[] lines, int start, int end) {
        String vn = Pattern.quote(varName);

        // First check: if variable is reassigned to different new Type() or has instanceof checks,
        // it's genuinely polymorphic — don't infer a specific type
        boolean hasMultipleTypes = false;
        String firstNewType = null;
        for (int j = start; j < end; j++) {
            String line = lines[j];
            if (Pattern.compile("\\b" + vn + "\\s+instanceof\\b").matcher(line).find()) {
                hasMultipleTypes = true;
                break;
            }
            Matcher nm = Pattern.compile("\\b" + vn + "\\s*=\\s*new (\\w+)\\(").matcher(line);
            if (nm.find()) {
                if (firstNewType == null) {
                    firstNewType = nm.group(1);
                } else if (!firstNewType.equals(nm.group(1))) {
                    hasMultipleTypes = true;
                    break;
                }
            }
        }
        if (hasMultipleTypes) return null;

        for (int j = start; j < end; j++) {
            String line = lines[j];

            // Assignment from readLine() → String
            if (Pattern.compile("\\b" + vn + "\\s*=\\s*\\w+\\.readLine\\(").matcher(line).find()) {
                return "String";
            }
            // String concatenation: var = var + "..." or var + "..."
            if (Pattern.compile("\\b" + vn + "\\s*=\\s*" + vn + "\\s*\\+\\s*\"").matcher(line).find()) {
                return "String";
            }
            if (Pattern.compile("\\b" + vn + "\\s*\\+\\s*\"").matcher(line).find()) {
                return "String";
            }
            // Method calls indicating String
            for (String method : STRING_ONLY_METHODS) {
                if (line.contains(varName + "." + method + "(")) return "String";
            }
            // Method calls indicating File
            for (String method : FILE_ONLY_METHODS) {
                if (line.contains(varName + "." + method + "(")) return "java.io.File";
            }
            // Method calls indicating Path
            for (String method : PATH_ONLY_METHODS) {
                if (line.contains(varName + "." + method + "(")) return "java.nio.file.Path";
            }
            // Method calls indicating InputStream
            if (Pattern.compile("\\b" + vn + "\\.read\\(").matcher(line).find()) return "java.io.InputStream";
            if (Pattern.compile("\\b" + vn + "\\.available\\(").matcher(line).find()) return "java.io.InputStream";
            if (Pattern.compile("\\b" + vn + "\\.close\\(").matcher(line).find() &&
                    !Pattern.compile("\\b" + vn + "\\.(?:delete|exists|mkdir)\\(").matcher(
                            String.join("\n", Arrays.copyOfRange(lines, start, end))).find()) {
                // close() exists on many types, only infer InputStream if no file-like methods
            }
            // Assignment from known return types
            if (Pattern.compile("\\b" + vn + "\\s*=\\s*[\\w.]+\\.getGridSquare\\(").matcher(line).find()) {
                return "IsoGridSquare";
            }
            if (Pattern.compile("\\b" + vn + "\\s*=\\s*[\\w.]+\\.ReadString\\(").matcher(line).find()) {
                return "String";
            }
            if (Pattern.compile("\\b" + vn + "\\s*=\\s*[\\w.]+\\.getSpriteNameFromID\\(").matcher(line).find()) {
                return "String";
            }
            // NumberFormat methods
            if (line.contains(varName + ".setGroupingUsed(") || line.contains(varName + ".setMaximumFractionDigits(")
                    || line.contains(varName + ".setMinimumFractionDigits(")) {
                return "java.text.NumberFormat";
            }
            // List methods (if both iterator and add are used, it's a List)
            if (line.contains(varName + ".iterator(") || line.contains(varName + ".add(")) {
                // Scan for the other method to confirm
                boolean hasIterator = false, hasAdd = false;
                for (int k = start; k < end; k++) {
                    if (lines[k].contains(varName + ".iterator(")) hasIterator = true;
                    if (lines[k].contains(varName + ".add(")) hasAdd = true;
                }
                if (hasIterator || hasAdd) return "java.util.List";
            }
        }
        return null;
    }

    // ========================================================================
    // Fix 14: Raw sort comparators with lambda field access
    // ========================================================================

    private static String fixRawSortComparators(String content) {
        // Fix: rawList.sort((a, b) -> a.field.compareTo(b.field))
        // where a and b are Object due to raw list
        // Need to infer type from field name using Vineflower naming

        // Fix: rawList.sort((a, b) -> intExpr) where a,b inferred as Object
        // Pattern: arrayList.sort((var1, var2) -> var2 - var1) for Integer comparisons
        content = content.replaceAll(
                "(\\w+)\\.sort\\((\\w+), (\\w+)\\) -> (\\3) - (\\2)\\)",
                "((java.util.List<Integer>)$1).sort(($2, $3) -> ((Integer)$3) - ((Integer)$2))"
        );

        return content;
    }

    // ========================================================================
    // Fix 15: DialogButton constructor ambiguity
    // ========================================================================

    private static String fixDialogButtonAmbiguity(String content) {
        if (!content.contains("new DialogButton(this,")) return content;
        // new DialogButton(this, 30, 225, ...) is ambiguous between (UIElement,float,float,...) and (UIEventHandler,int,int,...)
        // Fix: cast the int args to float to disambiguate
        content = content.replaceAll(
                "new DialogButton\\(this, (\\d+), (\\d+),",
                "new DialogButton(this, (float)$1, (float)$2,"
        );
        return content;
    }

    // ========================================================================
    // Fix 16: rawset ambiguity
    // ========================================================================

    private static String fixRawsetAmbiguity(String content) {
        if (!content.contains(".rawset(")) return content;
        // table.rawset(intExpr, value) is ambiguous between rawset(Object,Object) and rawset(int,Object)
        // Fix: cast to (Object) to disambiguate
        content = content.replaceAll(
                "\\.rawset\\((int\\d+), \\(double\\)",
                ".rawset((Object)$1, (double)"
        );
        return content;
    }

    // ========================================================================
    // Fix 17: Annotation type casts
    // ========================================================================

    private static String fixAnnotationTypeCasts(String content) {
        // .getAnnotation(Foo.class) returns Annotation instead of Foo
        // .getAnnotationsByType(Foo.class) returns Annotation[] instead of Foo[]
        content = content.replaceAll(
                "(\\w+) = (\\w+)\\.getAnnotation\\((\\w+)\\.class\\);",
                "$1 = ($3)$2.getAnnotation($3.class);"
        );
        content = content.replaceAll(
                "(\\w+)\\[\\] (\\w+) = (\\w+)\\.getAnnotationsByType\\((\\w+)\\.class\\);",
                "$1[] $2 = ($4[])$3.getAnnotationsByType($4.class);"
        );
        // return clazz.getAnnotationsByType(Type.class); — return statement
        content = content.replaceAll(
                "return (\\w+)\\.getAnnotationsByType\\((\\w+)\\.class\\);",
                "return ($2[])$1.getAnnotationsByType($2.class);"
        );
        return content;
    }

    // ========================================================================
    // Fix 18: DirectoryStream / Stream<Path> for-each
    // ========================================================================

    private static String fixDirectoryStreamForEach(String content) {
        // Detect DirectoryStream or Stream variables used in for-each
        // Pattern: DirectoryStream varName = ...  (raw, no generics)
        // Then: for (Path path : varName) needs cast
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        Set<String> dirStreamVars = new HashSet<>();
        Set<String> streamVars = new HashSet<>();
        for (String line : lines) {
            Matcher m = Pattern.compile("\\bDirectoryStream (\\w+)\\s*=").matcher(line);
            while (m.find()) dirStreamVars.add(m.group(1));
            m = Pattern.compile("\\bStream (\\w+)\\s*=").matcher(line);
            while (m.find()) streamVars.add(m.group(1));
        }

        for (int i = 0; i < lines.length; i++) {
            // Fix for-each on raw DirectoryStream
            Matcher fm = Pattern.compile(
                    "^(\\s*)for \\((\\w+) (\\w+) : (\\w+)\\)(.*)$"
            ).matcher(lines[i]);
            if (fm.matches()) {
                String collection = fm.group(4);
                String type = fm.group(2);
                if (dirStreamVars.contains(collection) && !type.equals("Object")) {
                    String indent = fm.group(1);
                    String var = fm.group(3);
                    String rest = fm.group(5);
                    lines[i] = indent + "for (" + type + " " + var + " : (Iterable<" + type + ">)(Iterable<?>)" + collection + ")" + rest;
                    modified = true;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 19: Switch on Object with int case labels
    // ========================================================================

    private static String fixSwitchOnObject(String content) {
        // When Vineflower emits switch(rawMap.get(...)) with int case labels,
        // the selector type is Object. Cast to int.
        // Pattern: switch (expr) { case INTEGER: ...
        // Detect by finding switch statements followed by int case labels
        // where the switch expression is a method call on a raw collection
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            Matcher sm = Pattern.compile("^(\\s*)switch \\((.+)\\) \\{$").matcher(lines[i]);
            if (!sm.matches()) continue;
            String indent = sm.group(1);
            String expr = sm.group(2);
            // Check if already cast
            if (expr.startsWith("(int)") || expr.startsWith("(Integer)")) continue;
            // Look ahead for case labels with int constants
            boolean hasIntCase = false;
            for (int j = i + 1; j < Math.min(i + 10, lines.length); j++) {
                if (Pattern.compile("^\\s*case \\d+").matcher(lines[j]).find()) {
                    hasIntCase = true;
                    break;
                }
                if (lines[j].trim().startsWith("}")) break;
            }
            if (hasIntCase) {
                // Only cast if expression involves a method call that returns Object
                // (e.g., map.get(), entry.getKey(), etc.)
                // Don't cast simple variables or expressions that are likely int/byte/short
                if (expr.contains(".get(") || expr.contains(".getKey(") || expr.contains(".rawget(")
                        || expr.contains(".getValue(")) {
                    lines[i] = indent + "switch ((int)(Integer)" + expr + ") {";
                    modified = true;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 20: java.io.File used as incorrect variable type
    // ========================================================================

    private static String fixMistypedJavaIoFile(String content) {
        if (!content.contains("java.io.File")) return content;
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = Pattern.compile("\\bjava\\.io\\.File (\\w+)\\s*[=;,)]").matcher(lines[i]);
            if (!m.find()) continue;
            String varName = m.group(1);
            String vn = Pattern.quote(varName);

            int scopeEnd = Math.min(findScopeEnd(lines, i + 1), lines.length);
            String correctType = null;

            for (int j = i; j < scopeEnd; j++) {
                String line = lines[j];
                // Assigned from rawget() → should be Object
                if (Pattern.compile("\\b" + vn + "\\s*=\\s*\\w+\\.rawget\\(").matcher(line).find()) {
                    correctType = "Object";
                    break;
                }
                // Used with instanceof non-File type → should be Object
                if (Pattern.compile("\\b" + vn + "\\s+instanceof\\s+(?!File\\b)\\w+").matcher(line).find()) {
                    correctType = "Object";
                    break;
                }
                // Used with .getClass() → should be Object
                if (line.contains(varName + ".getClass()")) {
                    correctType = "Object";
                    break;
                }
                // Assigned from new SomeNonFileType()
                Matcher nm = Pattern.compile("\\b" + vn + "\\s*=\\s*new (\\w+(?:\\.\\w+)*)\\(").matcher(line);
                if (nm.find()) {
                    String assignedType = nm.group(1);
                    if (!assignedType.equals("File") && !assignedType.startsWith("java.io.File")) {
                        // Find the root type (e.g., MemUtil.MemUtilNIO → MemUtil)
                        if (assignedType.contains(".")) {
                            correctType = assignedType.substring(0, assignedType.indexOf('.'));
                        } else {
                            correctType = "Object";
                        }
                        break;
                    }
                }
                // Cast to non-File type: return (SomeType)var
                Matcher cm = Pattern.compile("\\((?!File)(\\w+)\\)" + vn + "\\b").matcher(line);
                if (cm.find()) {
                    correctType = cm.group(1);
                    break;
                }
            }

            if (correctType != null) {
                lines[i] = lines[i].replaceFirst(
                        "java\\.io\\.File(?=\\s+" + vn + ")",
                        correctType
                );
                modified = true;
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 21: String variable assigned non-String type
    // ========================================================================

    private static String fixMistypedStringVar(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();
            // Match: String varName; (uninitialized)
            Matcher m = Pattern.compile("^String (\\w+);$").matcher(stripped);
            if (!m.matches()) continue;
            String varName = m.group(1);

            int scopeEnd = Math.min(findScopeEnd(lines, i + 1), lines.length);
            String vn = Pattern.quote(varName);

            for (int j = i + 1; j < scopeEnd; j++) {
                // Assigned from new DecimalFormat/NumberFormat.xxx → NumberFormat
                if (Pattern.compile("\\b" + vn + "\\s*=\\s*new DecimalFormat\\(").matcher(lines[j]).find() ||
                        Pattern.compile("\\b" + vn + "\\s*=\\s*NumberFormat\\.").matcher(lines[j]).find()) {
                    lines[i] = lines[i].replaceFirst("\\bString(?= " + vn + ";)", "NumberFormat");
                    modified = true;
                    break;
                }
                // Assigned from new Type that isn't String
                Matcher nm = Pattern.compile("\\b" + vn + "\\s*=\\s*new (\\w+)\\(").matcher(lines[j]);
                if (nm.find() && !nm.group(1).equals("String")) {
                    lines[i] = lines[i].replaceFirst("\\bString(?= " + vn + ";)", nm.group(1));
                    modified = true;
                    break;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 22: Object → var for generic method type inference
    // ========================================================================

    private static String fixObjectToVar(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();

            // Pattern: Object varName = expr; where expr is a method call or array access
            Matcher m = Pattern.compile("^Object (\\w+) = (.+);$").matcher(stripped);
            if (m.matches()) {
                String varName = m.group(1);
                String init = m.group(2).trim();
                // Only apply when init involves a method call or array/field access
                if (init.equals("null") || init.startsWith("\"") || init.startsWith("new ")
                        || init.equals("true") || init.equals("false")
                        || init.matches("-?\\d+[lLfFdD]?")) {
                    continue;
                }
                if (!init.contains(".") && !init.contains("[")) continue;
                // Skip lines that are method parameters (contain ( before Object)
                if (lines[i].contains("(") && lines[i].indexOf('(') < lines[i].indexOf("Object")) continue;
                // Skip if the variable is reassigned later (var would lock the inferred type)
                int scopeEnd = Math.min(findScopeEnd(lines, i + 1), lines.length);
                if (isReassigned(varName, lines, i + 1, scopeEnd)) continue;

                lines[i] = lines[i].replaceFirst("\\bObject(?= " + Pattern.quote(varName) + " =)", "var");
                modified = true;
                continue;
            }

            // Pattern: for (Object varName : collection)
            Matcher fm = Pattern.compile("^(\\s*)for \\(Object (\\w+) : (.+)\\)(.*)$").matcher(lines[i]);
            if (fm.matches()) {
                // Don't change if the collection already has an Iterable cast
                String collection = fm.group(3);
                if (collection.contains("Iterable<")) continue;

                lines[i] = fm.group(1) + "for (var " + fm.group(2) + " : " + fm.group(3) + ")" + fm.group(4);
                modified = true;
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 23: String variable assignment needs cast from Object-returning method
    // ========================================================================

    // Only methods that ALWAYS return Object (not overridden by generics)
    // Note: get/put/remove/putIfAbsent/getOrDefault are handled by fixRawCollectionTypes
    private static final Set<String> OBJECT_RETURNING_METHODS = Set.of(
            "getKey", "getValue", "key", "val"
    );

    private static String fixStringAssignmentNeedsCast(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        // Collect String variable names
        Set<String> stringVars = new HashSet<>();
        for (String line : lines) {
            Matcher sm = Pattern.compile("\\bString (\\w+)\\s*[=;,)]").matcher(line);
            while (sm.find()) stringVars.add(sm.group(1));
        }

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();

            // Pattern 1: String varName = expr.method(args);
            Matcher m = Pattern.compile(
                    "^String (\\w+) = ([\\w.]+)\\.(\\w+)\\((.*)$"
            ).matcher(stripped);
            if (m.matches()) {
                String method = m.group(3);
                if (OBJECT_RETURNING_METHODS.contains(method)) {
                    String indent = lines[i].substring(0, lines[i].length() - stripped.length());
                    String varName = m.group(1);
                    String expr = m.group(2);
                    String rest = m.group(4);
                    lines[i] = indent + "String " + varName + " = (String)" + expr + "." + method + "(" + rest;
                    modified = true;
                    continue;
                }
            }

            // Pattern 2: String varName = bareMethod(args); (no dot — internal method)
            Matcher m2 = Pattern.compile(
                    "^String (\\w+) = (\\w+)\\((.*)$"
            ).matcher(stripped);
            if (m2.matches()) {
                String method = m2.group(2);
                if (OBJECT_RETURNING_METHODS.contains(method)) {
                    String indent = lines[i].substring(0, lines[i].length() - stripped.length());
                    String varName = m2.group(1);
                    String rest = m2.group(3);
                    lines[i] = indent + "String " + varName + " = (String)" + method + "(" + rest;
                    modified = true;
                    continue;
                }
            }

            // Pattern 3: stringVar = expr.method(args); (reassignment)
            Matcher m3 = Pattern.compile(
                    "^(\\w+) = ([\\w.]+)\\.(\\w+)\\((.*)$"
            ).matcher(stripped);
            if (m3.matches() && stringVars.contains(m3.group(1))) {
                String method = m3.group(3);
                if (OBJECT_RETURNING_METHODS.contains(method)) {
                    String indent = lines[i].substring(0, lines[i].length() - stripped.length());
                    String varName = m3.group(1);
                    String expr = m3.group(2);
                    String rest = m3.group(4);
                    lines[i] = indent + varName + " = (String)" + expr + "." + method + "(" + rest;
                    modified = true;
                    continue;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 24: boolean → int and int → boolean conversions (IsoWorld etc.)
    // ========================================================================

    private static String fixBooleanIntConversion(String content) {
        // boolean cannot be converted to int: method returns boolean, assigned to int var
        // Pattern: int varName = method(); where method returns boolean
        // Fix: int varName = method() ? 1 : 0;
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        // Collect int variable declarations
        Set<String> intVars = new HashSet<>();
        for (String line : lines) {
            Matcher m = Pattern.compile("\\bint (\\w+)\\s*[=;,)]").matcher(line);
            while (m.find()) intVars.add(m.group(1));
        }

        for (int i = 0; i < lines.length; i++) {
            // Pattern: intVar = booleanMethod(args);
            // Known boolean-returning methods
            Matcher m = Pattern.compile(
                    "^(\\s*)(\\w+) = ([\\w.]+\\.(?:isPlayerAlive|isEmpty|contains|exists|isFile|isDirectory|startsWith|endsWith|equals|matches)\\(.+);$"
            ).matcher(lines[i]);
            if (m.matches() && intVars.contains(m.group(2))) {
                String indent = m.group(1);
                String varName = m.group(2);
                String expr = m.group(3);
                lines[i] = indent + varName + " = " + expr + " ? 1 : 0;";
                modified = true;
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 25: Raw lambda type inference using Vineflower naming convention
    // ========================================================================

    private static String inferTypeFromParamName(String paramName) {
        // Vineflower names lambda params based on their type:
        // hairStyle0 → HairStyle, debugType1 → DebugType, integer0 → Integer
        // pyramidTexturex → PyramidTexture (x suffix added by Vineflower to avoid conflicts)
        // Remove trailing digits
        String base = paramName.replaceAll("\\d+$", "");
        if (base.isEmpty()) return null;
        // Remove trailing 'x' suffix added by Vineflower (but not if it's a single char)
        if (base.length() > 1 && base.endsWith("x") &&
                Character.isLowerCase(base.charAt(base.length() - 2))) {
            base = base.substring(0, base.length() - 1);
        }
        // Capitalize first letter
        return base.substring(0, 1).toUpperCase() + base.substring(1);
    }

    private static String fixRawLambdaTypeInference(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        // Collect raw collection variable names
        Set<String> rawVars = new HashSet<>();
        for (String line : lines) {
            Matcher m = RAW_DECL_PATTERN.matcher(line);
            while (m.find()) {
                String prefix = line.substring(0, m.start(2));
                if (!prefix.contains("<")) {
                    rawVars.add(m.group(2));
                }
            }
        }
        // Also: Comparator varName = (a, b) -> ... (raw Comparator)
        for (String line : lines) {
            Matcher m = Pattern.compile("\\bComparator (\\w+)\\s*=").matcher(line);
            while (m.find()) {
                String prefix = line.substring(0, m.start(1));
                if (!prefix.contains("<")) {
                    rawVars.add(m.group(1));
                }
            }
        }

        if (rawVars.isEmpty()) return content;

        for (int i = 0; i < lines.length; i++) {
            // Pattern: rawVar.sort((param0, param1) -> ...
            for (String rawVar : rawVars) {
                String rv = Pattern.quote(rawVar);
                Matcher sm = Pattern.compile(
                        "(" + rv + ")\\.sort\\(\\((\\w+), (\\w+)\\) ->"
                ).matcher(lines[i]);
                if (sm.find()) {
                    String p1 = sm.group(2);
                    String type = inferTypeFromParamName(p1);
                    if (type != null) {
                        lines[i] = lines[i].substring(0, sm.start()) +
                                "((java.util.List<" + type + ">)" + rawVar + ").sort((" + p1 + ", " + sm.group(3) + ") ->" +
                                lines[i].substring(sm.end());
                        modified = true;
                    }
                }
            }

            // Pattern: rawVar.forEach((key, val) -> ... (Map forEach)
            for (String rawVar : rawVars) {
                String rv = Pattern.quote(rawVar);
                Matcher fm = Pattern.compile(
                        "(" + rv + ")\\.forEach\\(\\((\\w+), (\\w+)\\) ->"
                ).matcher(lines[i]);
                if (fm.find()) {
                    String keyParam = fm.group(2);
                    String valParam = fm.group(3);
                    String keyType = inferTypeFromParamName(keyParam);
                    String valType = inferTypeFromParamName(valParam);
                    if (keyType != null && valType != null) {
                        lines[i] = lines[i].substring(0, fm.start()) +
                                "((java.util.Map<" + keyType + ", " + valType + ">)" + rawVar + ").forEach((" + keyParam + ", " + valParam + ") ->" +
                                lines[i].substring(fm.end());
                        modified = true;
                    }
                }
            }

            // Pattern: Comparator.comparingInt((ToIntFunction)(param -> ((Object)param).field))
            // → Comparator.comparingInt((ToIntFunction)(param -> ((InferredType)param).field))
            Matcher cim = Pattern.compile(
                    "\\(\\(Object\\)(\\w+)\\)\\.(\\w+)"
            ).matcher(lines[i]);
            if (cim.find()) {
                String param = cim.group(1);
                String type = inferTypeFromParamName(param);
                if (type != null) {
                    lines[i] = lines[i].replace(
                            "((Object)" + param + ").",
                            "((" + type + ")" + param + ")."
                    );
                    modified = true;
                }
            }

            // Pattern: raw Comparator = (param0, param1) -> param0.method(param1)
            for (String rawVar : rawVars) {
                Matcher cm = Pattern.compile(
                        "(" + Pattern.quote(rawVar) + ")\\s*=\\s*\\((\\w+), (\\w+)\\) -> (\\2)\\.compareTo\\(\\3\\)"
                ).matcher(lines[i]);
                if (cm.find()) {
                    String p1 = cm.group(2);
                    String p2 = cm.group(3);
                    String type = inferTypeFromParamName(p1);
                    if (type != null) {
                        lines[i] = lines[i].substring(0, cm.start()) +
                                rawVar + " = (Comparator<" + type + ">)(Comparator<?>)(" + p1 + ", " + p2 + ") -> ((" + type + ")" + p1 + ").compareTo((" + type + ")" + p2 + ")" +
                                lines[i].substring(cm.end());
                        modified = true;
                    }
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 24: External references to shadowed fields (e.g., RenderThread.RenderThread)
    // ========================================================================

    private static String fixExternalShadowedFieldRefs(String content) {
        // When fixVariableShadowsClassName renames a static field that shadows
        // its class name, external files still reference ClassName.ClassName.
        // Replace these with ClassName.s_className.
        content = content.replace("RenderThread.RenderThread", "RenderThread.s_renderThread");
        return content;
    }

    // ========================================================================
    // Fix 24: Raw Stream<Path> and DirectoryStream.Filter<Path>
    // ========================================================================

    private static String fixRawStreamPath(String content) {
        // Stream stream = Files.walk/list/find(...) → Stream<Path> stream = ...
        content = content.replaceAll(
                "\\bStream (\\w+)(\\s*=\\s*Files\\.(?:walk|list|find)\\()",
                "Stream<java.nio.file.Path> $1$2"
        );
        // DirectoryStream dirStream = Files.newDirectoryStream(... → DirectoryStream<Path>
        content = content.replaceAll(
                "\\bDirectoryStream (\\w+)(\\s*=\\s*Files\\.newDirectoryStream\\()",
                "DirectoryStream<java.nio.file.Path> $1$2"
        );
        // Filter filter = path -> ... → DirectoryStream.Filter<Path> filter = ...
        // Pattern: Filter varName = lambdaParam -> ... (where filter is used with DirectoryStream)
        content = content.replaceAll(
                "\\bFilter (\\w+)(\\s*=\\s*\\w+ ->)",
                "java.nio.file.DirectoryStream.Filter<java.nio.file.Path> $1$2"
        );
        return content;
    }

    // ========================================================================
    // Fix 26: Empty switch expression case — add yield null
    // ========================================================================

    private static String fixEmptySwitchExpressionCase(String content) {
        // Fix switch expressions with empty case blocks and missing default
        String[] lines = content.split("\n", -1);
        boolean modified = false;
        boolean inSwitchExpr = false;
        int switchExprStart = -1;
        int switchDepth = 0;

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("switch (") || lines[i].contains("switch(")) {
                // Check if this is a switch expression (not a switch statement)
                // Switch expressions have arrow cases (->), switch statements have colon cases (:)
                boolean isExpression = false;
                for (int peek = i + 1; peek < Math.min(i + 5, lines.length); peek++) {
                    if (lines[peek].trim().startsWith("case ") && lines[peek].contains("->")) {
                        isExpression = true;
                        break;
                    }
                    if (lines[peek].trim().startsWith("case ") && lines[peek].contains(":")) {
                        break;
                    }
                }
                if (isExpression) {
                    inSwitchExpr = true;
                    switchExprStart = i;
                    switchDepth = 0;
                }
            }
            if (inSwitchExpr) {
                switchDepth += countChar(lines[i], '{') - countChar(lines[i], '}');
                if (switchDepth <= 0) {
                    // End of switch expression — check if it has a default case
                    boolean hasDefault = false;
                    for (int j = switchExprStart; j <= i; j++) {
                        if (lines[j].trim().startsWith("default")) {
                            hasDefault = true;
                            break;
                        }
                    }
                    if (!hasDefault) {
                        // Find the closing } and add default before it
                        // The closing line could be "};" or "});" or similar
                        for (int j = i; j >= switchExprStart; j--) {
                            String trimmed = lines[j].trim();
                            if (trimmed.startsWith("}") && (trimmed.equals("};") || trimmed.equals("});") || trimmed.equals("})"))) {
                                String indent = lines[j].substring(0, lines[j].indexOf("}"));
                                // Determine the default yield value based on the type context
                                String switchLine = lines[switchExprStart];
                                String defaultYield;
                                // Check if the variable being assigned is a primitive numeric type
                                boolean isNumeric = Pattern.compile(
                                        "\\b(?:int|float|double|long|short|byte)\\d*\\s*=\\s*switch"
                                ).matcher(switchLine).find()
                                || Pattern.compile("\\b(?:int|float|double|long|short|byte) \\w+\\s*=\\s*switch").matcher(switchLine).find();
                                // Check if it's a method argument (no variable assignment)
                                boolean isArg = !switchLine.contains("= switch") && switchLine.contains("switch (");
                                // Use throw for all cases — it's a safe unreachable default
                                // that works for any return type
                                defaultYield = "default -> throw new IllegalStateException();";
                                lines[j] = indent + "   " + defaultYield + "\n" + lines[j];
                                modified = true;
                                break;
                            }
                        }
                    }
                    inSwitchExpr = false;
                }

                // Match: case N -> {\n  }  (empty block — needs yield)
                Matcher cm = Pattern.compile("^(\\s*)case .+ -> \\{\\s*$").matcher(lines[i]);
                if (cm.matches() && i + 1 < lines.length && lines[i + 1].trim().equals("}")) {
                    String bodyIndent = cm.group(1) + "   ";
                    lines[i] = lines[i] + "\n" + bodyIndent + "yield null;";
                    modified = true;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 27b: CloneNotSupportedException never thrown — widen to Exception
    // ========================================================================

    private static String fixUncaughtExceptionInTry(String content) {
        // When decompiler emits catch(CloneNotSupportedException) but the try body
        // doesn't actually throw it, the compiler errors.
        // Fix: widen to Exception
        if (content.contains("catch (CloneNotSupportedException")) {
            content = content.replace(
                    "catch (CloneNotSupportedException cloneNotSupportedException)",
                    "catch (Exception cloneNotSupportedException)"
            );
        }
        return content;
    }

    // ========================================================================
    // Fix 28: Raw .toArray() returning Object[] — add typed cast
    // ========================================================================

    private static String fixRawToArrayCast(String content) {
        // Pattern: Type[] var = expr.toArray(new Type[N]) or expr.toArray(var)
        // where the list is raw, so toArray returns Object[] not Type[]
        // Fix: insert (Type[]) cast
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            // Pattern A: Type[] varName = expr.toArray(new Type[...]);
            Matcher m = Pattern.compile(
                    "^(\\s*)(\\w+(?:\\.\\w+)?)(\\[\\]) (\\w+) = (.+)\\.toArray\\((new \\2\\[.+?\\])\\);$"
            ).matcher(lines[i]);
            if (m.matches()) {
                String indent = m.group(1);
                String type = m.group(2);
                String var = m.group(4);
                String expr = m.group(5);
                String arg = m.group(6);
                // Don't double-cast
                if (!expr.endsWith(")") || !lines[i].contains("(" + type + "[])" )) {
                    lines[i] = indent + type + "[] " + var + " = (" + type + "[])" + expr + ".toArray(" + arg + ");";
                    modified = true;
                    continue;
                }
            }

            // Pattern B: Type[] varName = expr.toArray(existingVar);
            Matcher m2 = Pattern.compile(
                    "^(\\s*)(\\w+(?:\\.\\w+)?)(\\[\\]) (\\w+) = (.+)\\.toArray\\((\\w+)\\);$"
            ).matcher(lines[i]);
            if (m2.matches()) {
                String indent = m2.group(1);
                String type = m2.group(2);
                String var = m2.group(4);
                String expr = m2.group(5);
                String arg = m2.group(6);
                if (!lines[i].contains("(" + type + "[])")) {
                    lines[i] = indent + type + "[] " + var + " = (" + type + "[])" + expr + ".toArray(" + arg + ");";
                    modified = true;
                    continue;
                }
            }

            // Pattern C: varName = expr.toArray(existingVar); (reassignment, need to find type from context)
            Matcher m3 = Pattern.compile(
                    "^(\\s*)(\\w+\\.\\w+) = (.+)\\.toArray\\((\\w+\\.\\w+)\\);$"
            ).matcher(lines[i]);
            if (m3.matches()) {
                String indent = m3.group(1);
                String targetField = m3.group(2);
                String expr = m3.group(3);
                String arg = m3.group(4);
                // Look backwards for the type of the target field
                for (int j = Math.max(0, i - 30); j < i; j++) {
                    Matcher typeM = Pattern.compile("(\\w+(?:\\.\\w+)?)\\[] " + Pattern.quote(targetField.split("\\.")[1]) + "\\b").matcher(lines[j]);
                    if (typeM.find()) {
                        String type = typeM.group(1);
                        lines[i] = indent + targetField + " = (" + type + "[])" + expr + ".toArray(" + arg + ");";
                        modified = true;
                        break;
                    }
                }
            }

            // Pattern D: ternary with toArray: expr ? new Type[0] : var.toArray(new Type[N])
            Matcher m4 = Pattern.compile(
                    "(\\? new (\\w+)\\[0\\] : (\\w+)\\.toArray\\(new \\2\\[)(.+?\\]\\))"
            ).matcher(lines[i]);
            if (m4.find()) {
                String type = m4.group(2);
                if (!lines[i].contains("(" + type + "[])")) {
                    lines[i] = lines[i].substring(0, m4.start()) +
                            "? new " + type + "[0] : (" + type + "[])" + m4.group(3) + ".toArray(new " + type + "[" + m4.group(4) +
                            lines[i].substring(m4.end());
                    modified = true;
                }
            }

            // Pattern E: return expr.toArray(new Type[N]);
            Matcher m5 = Pattern.compile(
                    "^(\\s*)return (.+)\\.toArray\\((new (\\w+)\\[.+?\\])\\);$"
            ).matcher(lines[i]);
            if (m5.matches()) {
                String indent = m5.group(1);
                String expr = m5.group(2);
                String arg = m5.group(3);
                String type = m5.group(4);
                if (!lines[i].contains("(" + type + "[])")) {
                    lines[i] = indent + "return (" + type + "[])" + expr + ".toArray(" + arg + ");";
                    modified = true;
                    continue;
                }
            }

            // Pattern F: field.field = expr.toArray(field.field); (field assignment)
            Matcher m6 = Pattern.compile(
                    "^(\\s*)(\\w+\\.\\w+) = (.+)\\.toArray\\(\\2\\);$"
            ).matcher(lines[i]);
            if (m6.matches()) {
                String indent = m6.group(1);
                String target = m6.group(2);
                String expr = m6.group(3);
                // Look backward for the type
                String fieldName = target.split("\\.")[1];
                for (int j = Math.max(0, i - 50); j < i; j++) {
                    Matcher typeM = Pattern.compile("(\\w+(?:\\.\\w+)?)\\[\\]\\s+" + Pattern.quote(fieldName) + "\\b").matcher(lines[j]);
                    if (typeM.find()) {
                        String type = typeM.group(1);
                        lines[i] = indent + target + " = (" + type + "[])" + expr + ".toArray(" + target + ");";
                        modified = true;
                        break;
                    }
                }
            }

            // Pattern G: this.field = expr.toArray(this.field);
            Matcher m7 = Pattern.compile(
                    "^(\\s*)(this\\.\\w+) = (.+)\\.toArray\\(\\2\\);$"
            ).matcher(lines[i]);
            if (m7.matches()) {
                String indent = m7.group(1);
                String target = m7.group(2);
                String expr = m7.group(3);
                String fieldName = target.substring(5); // strip "this."
                // Search class fields for type
                for (int j = 0; j < lines.length; j++) {
                    Matcher typeM = Pattern.compile("(\\w+(?:\\.\\w+)?)\\[\\]\\s+" + Pattern.quote(fieldName) + "\\b").matcher(lines[j]);
                    if (typeM.find()) {
                        String type = typeM.group(1);
                        lines[i] = indent + target + " = (" + type + "[])" + expr + ".toArray(" + target + ");";
                        modified = true;
                        break;
                    }
                }
            }

            // Pattern H: Generic fallback — any X = expr.toArray(X); where the result type should match
            // Detect: if line has ".toArray(" and the compile would fail, search whole file for type
            if (!modified && lines[i].contains(".toArray(") && !lines[i].contains("[])")  ) {
                Matcher mg = Pattern.compile(
                        "^(\\s*)(\\w+(?:\\.\\w+)*) = (.+)\\.toArray\\((.+)\\);$"
                ).matcher(lines[i]);
                if (mg.matches()) {
                    String indent = mg.group(1);
                    String target = mg.group(2);
                    String expr = mg.group(3);
                    String arg = mg.group(4);
                    // Search the entire file for the field type declaration
                    String lastPart = target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : target;
                    for (int j = 0; j < lines.length; j++) {
                        Matcher typeM = Pattern.compile("(\\w+(?:\\.\\w+)?)\\[\\]\\s+" + Pattern.quote(lastPart) + "\\b").matcher(lines[j]);
                        if (typeM.find()) {
                            String type = typeM.group(1);
                            if (!lines[i].contains("(" + type + "[])")) {
                                lines[i] = indent + target + " = (" + type + "[])" + expr + ".toArray(" + arg + ");";
                                modified = true;
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 27: Raw for-each — cast iterable to typed
    // ========================================================================

    private static String fixRawForEachCast(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            // Pattern: for (TypeName var : (RawType)expr.method()) {
            // where the cast loses generic info
            Matcher m = Pattern.compile(
                    "^(\\s*)for \\((\\w+(?:<[^>]+>)?) (\\w+) : \\((ArrayList|HashMap|Map|Set|Collection)\\)(\\w+)\\.([\\w.]+\\([^)]*\\))\\)(.*)$"
            ).matcher(lines[i]);
            if (m.matches()) {
                String indent = m.group(1);
                String type = m.group(2);
                String var = m.group(3);
                String rawCast = m.group(4);
                String obj = m.group(5);
                String method = m.group(6);
                String rest = m.group(7);
                lines[i] = indent + "for (" + type + " " + var + " : (Iterable<" + type + ">)(Iterable<?>)" + obj + "." + method + ")" + rest;
                modified = true;
                continue;
            }

            // Pattern: for (TypeName var : expr.method()) where method returns raw collection
            // Specifically handle: for (Type var : this.loadScripts(args))
            Matcher m2 = Pattern.compile(
                    "^(\\s*)for \\((\\w+) (\\w+) : (this\\.\\w+\\([^)]+\\))\\)(.*)$"
            ).matcher(lines[i]);
            if (m2.matches()) {
                String indent = m2.group(1);
                String type = m2.group(2);
                String var = m2.group(3);
                String expr = m2.group(4);
                String rest = m2.group(5);
                // Check if this is a typed for-each over a raw method return
                // Only apply if the method name suggests it returns a collection
                if (expr.contains("loadScripts")) {
                    lines[i] = indent + "for (" + type + " " + var + " : (Iterable<" + type + ">)(Iterable<?>)" + expr + ")" + rest;
                    modified = true;
                    continue;
                }
            }

            // Handled by fixSpecificFileErrors for known cases

            // Pattern: for (TypeName var : new ArrayList(expr)) — raw ArrayList copy in for-each
            // Fix: add diamond operator to ArrayList to carry the type
            Matcher m3 = Pattern.compile(
                    "^(\\s*)for \\((\\w+(?:\\.\\w+)*) (\\w+) : new ArrayList\\((.+?)\\)\\)(.*)$"
            ).matcher(lines[i]);
            if (m3.matches()) {
                String indent = m3.group(1);
                String type = m3.group(2);
                String var = m3.group(3);
                String expr = m3.group(4);
                String rest = m3.group(5);
                lines[i] = indent + "for (" + type + " " + var + " : new ArrayList<" + type + ">(" + expr + "))" + rest;
                modified = true;
                continue;
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 28: Raw method return assigned to typed variable — add cast
    // ========================================================================

    private static String fixRawMethodReturnCast(String content) {
        // Pattern: TypeName var = rawExpr.method(args);
        // where method returns Object due to raw type
        // Known patterns: pickRandom, putIfAbsent, loadScripts
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        // Collect raw variables
        Set<String> rawVars = new HashSet<>();
        for (String line : lines) {
            Matcher m = RAW_DECL_PATTERN.matcher(line);
            while (m.find()) {
                String prefix = line.substring(0, m.start(2));
                if (!prefix.contains("<")) {
                    rawVars.add(m.group(2));
                }
            }
        }

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();

            // Pattern: Type var = SomeClass.pickRandom(list) or OutfitRNG.pickRandom(list)
            // pickRandom is a generic method that returns Object when type inference fails
            Matcher m = Pattern.compile(
                    "^(\\w+(?:\\.\\w+)?) (\\w+) = (\\w+\\.pickRandom\\()(.+)(\\);.*)$"
            ).matcher(stripped);
            if (m.matches()) {
                String type = m.group(1);
                String var = m.group(2);
                String pre = m.group(3);
                String arg = m.group(4);
                String post = m.group(5);
                if (!type.equals("Object")) {
                    String indent = lines[i].substring(0, lines[i].length() - stripped.length());
                    lines[i] = indent + type + " " + var + " = (" + type + ")" + pre + arg + post;
                    modified = true;
                    continue;
                }
            }

            // Reassignment: var = SomeClass.pickRandom(list);
            Matcher mReassign = Pattern.compile(
                    "^(\\w+) = (\\w+\\.pickRandom\\()(.+)(\\);.*)$"
            ).matcher(stripped);
            if (mReassign.matches()) {
                String var = mReassign.group(1);
                String pre = mReassign.group(2);
                String arg = mReassign.group(3);
                String post = mReassign.group(4);
                // Find the declared type of this variable
                for (int j = Math.max(0, i - 40); j < i; j++) {
                    Matcher typeM = Pattern.compile("(\\w+(?:\\.\\w+)?) " + Pattern.quote(var) + "\\s*[=;]").matcher(lines[j]);
                    if (typeM.find() && !typeM.group(1).equals("Object") && !typeM.group(1).equals("var")) {
                        String type = typeM.group(1);
                        String indent = lines[i].substring(0, lines[i].length() - stripped.length());
                        lines[i] = indent + var + " = (" + type + ")" + pre + arg + post;
                        modified = true;
                        break;
                    }
                }
            }

            // Pattern: TypeName var = rawMap.putIfAbsent(key, val);
            Matcher m2 = Pattern.compile(
                    "^(\\w+(?:\\.\\w+)?) (\\w+) = (\\w+)\\.putIfAbsent\\((.+)$"
            ).matcher(stripped);
            if (m2.matches()) {
                String type = m2.group(1);
                String var = m2.group(2);
                String map = m2.group(3);
                String rest = m2.group(4);
                if (!type.equals("Object") && rawVars.contains(map)) {
                    String indent = lines[i].substring(0, lines[i].length() - stripped.length());
                    lines[i] = indent + type + " " + var + " = (" + type + ")" + map + ".putIfAbsent(" + rest;
                    modified = true;
                    continue;
                }
            }

            // Pattern: var = rawMap.putIfAbsent(key, val); (reassignment)
            Matcher m3 = Pattern.compile(
                    "^(\\w+) = (\\w+)\\.putIfAbsent\\((.+)$"
            ).matcher(stripped);
            if (m3.matches() && !m3.group(1).equals("var")) {
                String var = m3.group(1);
                String map = m3.group(2);
                String rest = m3.group(3);
                if (rawVars.contains(map)) {
                    // Find the type from prior declaration
                    for (int j = Math.max(0, i - 30); j < i; j++) {
                        Matcher typeM = Pattern.compile("(\\w+(?:\\.\\w+)?) " + Pattern.quote(var) + "\\s*[=;]").matcher(lines[j]);
                        if (typeM.find() && !typeM.group(1).equals("Object") && !typeM.group(1).equals("var")) {
                            String type = typeM.group(1);
                            String indent = lines[i].substring(0, lines[i].length() - stripped.length());
                            lines[i] = indent + var + " = (" + type + ")" + map + ".putIfAbsent(" + rest;
                            modified = true;
                            break;
                        }
                    }
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // Fix 29: Generic class internal type errors
    // ========================================================================

    private static String fixGenericClassInternals(String content) {
        boolean modified = false;

        // ZomboidHashMap: entry.value returns Object instead of V
        // Fix: cast to (V) — but this requires @SuppressWarnings, so instead use raw entry.value
        // Actually the issue is that Entry is raw. The pattern is:
        //   for (ZomboidHashMap.Entry entry = ...; ...)
        //   return entry.value;  // Object, but method signature says V
        // Fix: return (V)entry.value;
        if (content.contains("class ZomboidHashMap")) {
            // return entry.value; → return (V)entry.value;
            content = content.replaceAll(
                    "return entry\\.value;",
                    "return (V)entry.value;"
            );
            // return entry == null ? null : entry.value;
            content = content.replaceAll(
                    "return entry == null \\? null : entry\\.value;",
                    "return entry == null ? null : (V)entry.value;"
            );
            modified = true;
        }

        // NonBlockingHashMap, NonBlockingIdentityHashMap, NonBlockingHashtable:
        // NBHMEntry(this._ss._prevK, this._ss._prevV) — _prevK is Object, needs (TypeK) cast
        for (String cls : List.of("NonBlockingHashMap", "NonBlockingIdentityHashMap", "NonBlockingHashtable")) {
            if (content.contains("class " + cls)) {
                content = content.replace(
                        cls + ".this.new NBHMEntry(this._ss._prevK, this._ss._prevV)",
                        cls + ".this.new NBHMEntry((TypeK)this._ss._prevK, this._ss._prevV)"
                );
                modified = true;
            }
        }

        // FibonacciHeap: Vineflower hardcoded IsoGridSquare instead of using type parameter T
        // Replace Entry<IsoGridSquare> with Entry<T> in method signatures and bodies
        if (content.contains("class FibonacciHeap<T>")) {
            // Remove the import since it won't be needed after fix
            content = content.replace("import zombie.iso.IsoGridSquare;\n", "");
            // Replace all Entry<IsoGridSquare> with Entry<T>
            content = content.replace("Entry<IsoGridSquare>", "Entry<T>");
            // Replace method names that Vineflower duplicated with concrete type
            // cutNodeNode and decreaseKeyUncheckedNode are duplicates of cutNode and decreaseKeyUnchecked
            // with IsoGridSquare hardcoded — replace them to call the generic versions
            content = content.replace("this.cutNodeNode(entry)", "this.cutNode(entry)");
            content = content.replace("this.decreaseKeyUncheckedNode(entry", "this.decreaseKeyUnchecked(entry");
            // Rename the methods themselves
            content = content.replaceAll(
                    "private void cutNodeNode\\(FibonacciHeap\\.Entry<T>",
                    "private void cutNodeNode_unused(FibonacciHeap.Entry<T>"
            );
            content = content.replaceAll(
                    "private void decreaseKeyUncheckedNode\\(FibonacciHeap\\.Entry<T>",
                    "private void decreaseKeyUncheckedNode_unused(FibonacciHeap.Entry<T>"
            );
            // Also fix the delete method that still references IsoGridSquare
            content = content.replace(
                    "public void delete(int var1, IsoGridSquare var2)",
                    "public void delete(int var1, T var2)"
            );
            modified = true;
        }

        // Pool<PO>: iPooledObject.setPool(this) — Pool<PO> can't convert to Pool<IPooledObject>
        // Fix: add unchecked cast
        if (content.contains("class Pool<PO")) {
            content = content.replace(
                    "iPooledObject.setPool(this);",
                    "iPooledObject.setPool((Pool<IPooledObject>)(Pool<?>)this);"
            );
            modified = true;
        }

        // Lambda.java: (T)functionx.apply(...) — T is not in scope for the lambda
        // Fix: remove the (T) cast, use Object
        if (content.contains("class Lambda")) {
            content = content.replace(
                    "returnValueContainerx.ReturnVal = (T)functionx.apply(",
                    "returnValueContainerx.ReturnVal = functionx.apply("
            );
            modified = true;
        }

        // PrimitiveFloatList: objects[int1] = float0; where objects is T[] and float0 is Float
        // Fix: unchecked cast (T)(Object)float0
        if (content.contains("class PrimitiveFloatList")) {
            // Fix toArray: objects[int1] = float0; → objects[int1] = (T)(Object)float0;
            content = content.replaceAll(
                    "(objects\\[int1\\]) = float0;",
                    "$1 = (T)(Object)float0;"
            );
            // Fix ambiguous forEach: this.forEach(consumer::accept) is ambiguous
            // between forEach(Consumer<? super Float>) and forEach(FloatConsumer)
            // Fix: cast the method reference
            content = content.replace(
                    "this.forEach(consumer::accept);",
                    "this.forEach((FloatConsumer)consumer::accept);"
            );
            modified = true;
        }

        // PZConvertList/PZConvertArray: objects[int1] = object; where objects is R[] and object is T
        // Fix: unchecked cast (R)(Object)object
        if (content.contains("class PZConvertList")) {
            content = content.replace(
                    "objects[int1] = object;",
                    "objects[int1] = (R)(Object)object;"
            );
            modified = true;
        }
        if (content.contains("class PZConvertArray")) {
            content = content.replace(
                    "objects[int1] = object;",
                    "objects[int1] = (R)(Object)object;"
            );
            modified = true;
        }

        // PZArrayUtil: Object → E in find(), Object → V in getOrCreate()
        if (content.contains("class PZArrayUtil")) {
            // predicate.test(object1) where object1 is Object but should be E
            // The issue is: } while (!predicate.test(object1)); where object1 = iterator.next() (raw)
            // Fix: cast object1 to (E)
            content = content.replace(
                    "} while (!predicate.test(object1));",
                    "} while (!predicate.test((E)object1));"
            );
            // hashMap.put(object1, object0); where object0 is Object but should be V
            // The var: Object object0 = hashMap.get(object1); then object0 = supplier.get();
            // Fix: cast object0 at put call
            content = content.replace(
                    "hashMap.put(object1, object0);",
                    "hashMap.put(object1, (V)object0);"
            );
            modified = true;
        }

        // KahluaConverterManager: several generic issues
        if (content.contains("class KahluaConverterManager")) {
            // Line 68: Object var3 = (Map)var1.get(var2); — var3 should be Map type
            // Already cast to (Map), but declared as Object. The return type of getOrCreate is Map<...>
            // Actually the issue is: var1.put(var2, var3) where var3 is Object
            // Fix: the variable is already assigned from (Map), so change declaration
            content = content.replace(
                    "Object var3 = (Map)var1.get(var2);",
                    "Map<Class, LuaToJavaConverter> var3 = (Map<Class, LuaToJavaConverter>)(Map)var1.get(var2);"
            );
            // Line 98: return this.tryInterfaces(var4, var2, var1); — returns Object instead of T
            // tryInterfaces returns T, but var4 is raw Map
            // The issue is: tryInterfaces(Map<Class,LuaToJavaConverter>, Class<T>, Object) return T
            // but it's being called with a raw Map, so T doesn't bind.
            // Fix: cast return
            content = content.replaceAll(
                    "return this\\.tryInterfaces\\(var4, var2, var1\\);",
                    "return (T)this.tryInterfaces(var4, var2, var1);"
            );
            // Line 115: (Iterable<Class>)(Iterable<?>)var2.getInterfaces() — Class[] is not Iterable
            // getInterfaces() returns Class<?>[], which IS iterable in for-each (arrays work)
            // The problem is the (Iterable<Class>)(Iterable<?>) cast — arrays don't implement Iterable
            // Fix: remove the Iterable cast, just use the array directly
            content = content.replace(
                    "for (Class var8 : (Iterable<Class>)(Iterable<?>)var2.getInterfaces())",
                    "for (Class var8 : var2.getInterfaces())"
            );
            // Line 122: return this.tryInterfaces(var1, var2.getSuperclass(), var3); — inference fail
            // var2.getSuperclass() returns Class<? super T>, but tryInterfaces wants Class<T>
            // Fix: unchecked cast
            content = content.replace(
                    "return this.tryInterfaces(var1, var2.getSuperclass(), var3);",
                    "return (T)this.tryInterfaces(var1, (Class)var2.getSuperclass(), var3);"
            );
            modified = true;
        }

        // CommandBase: getAnnotation return type
        if (content.contains("class CommandBase")) {
            // Line 357: return clazz0.getAnnotation(clazz1); — args are swapped
            // The method is: <T> T getAnnotation(Class<T> clazz1, Class clazz0)
            // clazz0.getAnnotation(clazz1) returns Annotation, not T
            // The parameters are likely swapped by decompiler
            // Fix: cast the return
            content = content.replace(
                    "return clazz0.getAnnotation(clazz1);",
                    "return (T)clazz0.getAnnotation(clazz1);"
            );
            modified = true;
        }

        if (!modified) return content;
        return content;
    }

    // ========================================================================
    // Fix 30: File-specific error fixes
    // ========================================================================

    private static String fixSpecificFileErrors(String content) {
        boolean modified = false;

        // ClothingWetness: clothing0 is an instanceof pattern variable scoped to the while loop body
        // but used outside at line 362. The variable escapes scope via label85.
        // Fix: declare clothing0 before the label85 block and cast manually
        if (content.contains("class ClothingWetness")) {
            // The pattern: inside label85 block, "if (item1 instanceof Clothing clothing0)"
            // then after the while loop: "clothing0.setWetness(clothing0.getWetness() + float1);"
            // clothing0 is not in scope there.
            // Fix: Replace "if (item1 instanceof Clothing clothing0)" with plain instanceof + cast
            // and declare clothing0 before the label block
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                // Find "label85: {" and inject Clothing clothing0 = null; before it
                if (lines[i].trim().equals("label85: {")) {
                    // Find indent
                    String indent = lines[i].substring(0, lines[i].indexOf("label85"));
                    lines[i] = indent + "Clothing clothing0 = null;\n" + lines[i];
                    modified = true;
                }
                // Replace "if (item1 instanceof Clothing clothing0) {" with plain check + cast
                if (lines[i].contains("if (item1 instanceof Clothing clothing0)")) {
                    lines[i] = lines[i].replace(
                            "if (item1 instanceof Clothing clothing0)",
                            "if (item1 instanceof Clothing)"
                    );
                    // Add cast as next line
                    String bodyIndent = "";
                    if (i + 1 < lines.length) {
                        Matcher indM = Pattern.compile("^(\\s*)").matcher(lines[i + 1]);
                        if (indM.find()) bodyIndent = indM.group(1);
                    }
                    lines[i] = lines[i] + "\n" + bodyIndent + "clothing0 = (Clothing)item1;";
                    modified = true;
                }
            }
            if (modified) content = String.join("\n", lines);
        }

        // IsoGridSquare: "if (player instanceof IsoSurvivor)" — IsoPlayer cannot narrow to IsoSurvivor
        // This is a decompiler bug. The bytecode likely checks if the player variable
        // (which could be any IsoMovingObject) is an IsoSurvivor.
        // But Vineflower already narrowed `player` to IsoPlayer type via earlier instanceof.
        // Fix: check with IsoGameCharacter (common supertype) or just keep the check
        // Actually, the real fix: just change it to instanceof check on the actual runtime type
        if (content.contains("class IsoGridSquare") && content.contains("player instanceof IsoSurvivor")) {
            // The player variable is typed as IsoPlayer but might actually be an IsoSurvivor at runtime
            // The original bytecode does instanceof IsoSurvivor. Just leave it as is but cast.
            // Actually the issue is that IsoPlayer is NOT a subclass of IsoSurvivor.
            // So this check always returns false. The decompiler should have emitted:
            // if (player instanceof IsoSurvivor) — but player is an IsoPlayer which can never be IsoSurvivor.
            // This is dead code. Fix: comment it out / make it always false.
            content = content.replace(
                    "if (player instanceof IsoSurvivor)",
                    "if (false /* player instanceof IsoSurvivor */)"
            );
            modified = true;
        }

        // HairOutfitDefinitions: OutfitRNG.pickRandom(arrayList0).name — returns Object, no .name field
        // arrayList0 is raw ArrayList, pickRandom returns Object
        // The items in arrayList0 are HairStyle objects (from the method signature/context)
        // Fix: cast pickRandom result to HairStyle
        if (content.contains("class HairOutfitDefinitions")) {
            content = content.replace(
                    "OutfitRNG.pickRandom(arrayList0).name",
                    "((HairStyle)OutfitRNG.pickRandom(arrayList0)).name"
            );
            modified = true;
        }

        // KahluaThread: String var210 = var1.get(var47); — var1 is LuaCallFrame, get returns Object
        // Fix: cast to (String) ONLY for String-typed variables (not var)
        if (content.contains("class KahluaThread")) {
            // Declaration: String varName = var1.get(...)
            content = content.replaceAll(
                    "String (var\\d+) = var1\\.get\\((var\\d+)\\);",
                    "String $1 = (String)var1.get($2);"
            );
            // Reassignment: track String-declared vars and only cast those
            String[] ktLines = content.split("\n", -1);
            Set<String> stringVarsKT = new HashSet<>();
            for (String line : ktLines) {
                Matcher sm = Pattern.compile("\\bString (var\\d+)\\b").matcher(line);
                while (sm.find()) stringVarsKT.add(sm.group(1));
            }
            for (int ki = 0; ki < ktLines.length; ki++) {
                for (String sv : stringVarsKT) {
                    String pat = sv + " = var1.get(";
                    if (ktLines[ki].contains(pat) && !ktLines[ki].contains("(String)var1.get(")) {
                        ktLines[ki] = ktLines[ki].replace(
                                sv + " = var1.get(",
                                sv + " = (String)var1.get("
                        );
                    }
                }
            }
            content = String.join("\n", ktLines);
            modified = true;
        }

        // SandboxOptions: for (Type var : (ArrayList)hashMap.get("")) — raw ArrayList
        // Fix: add Iterable cast
        if (content.contains("class SandboxOptions")) {
            content = content.replaceAll(
                    "for \\(SandboxOptions\\.SandboxOption (\\w+) : \\(ArrayList\\)hashMap\\.get\\(([^)]+)\\)\\)",
                    "for (SandboxOptions.SandboxOption $1 : (Iterable<SandboxOptions.SandboxOption>)(Iterable<?>)(ArrayList)hashMap.get($2))"
            );
            modified = true;
        }

        // ZomboidRadio: for (Entry entry1 : ((Map)entry0.getValue()).entrySet())
        if (content.contains("class ZomboidRadio")) {
            content = content.replace(
                    "for (Entry entry1 : ((Map)entry0.getValue()).entrySet())",
                    "for (Entry entry1 : (Iterable<Entry>)(Iterable<?>)((Map)entry0.getValue()).entrySet())"
            );
            modified = true;
        }

        // IsoWorld: for (String string : (ArrayList)entry.getValue())
        if (content.contains("class IsoWorld") && content.contains("for (String string : (ArrayList)entry.getValue())")) {
            content = content.replace(
                    "for (String string : (ArrayList)entry.getValue())",
                    "for (String string : (Iterable<String>)(Iterable<?>)(ArrayList)entry.getValue())"
            );
            modified = true;
        }

        // RoomDef: hashSet.forEach(chunkx -> biConsumer.accept(this, chunkx))
        // hashSet is raw HashSet, forEach expects Consumer<Object>
        // but biConsumer.accept(this, chunkx) expects IsoChunk for second arg
        if (content.contains("class RoomDef") && content.contains("hashSet.forEach(chunkx -> biConsumer.accept(this, chunkx))")) {
            content = content.replace(
                    "hashSet.forEach(chunkx -> biConsumer.accept(this, chunkx))",
                    "hashSet.forEach(chunkx -> biConsumer.accept(this, (IsoChunk)chunkx))"
            );
            modified = true;
        }

        // KahluaTableConverter: for (Entry var5 : var1x.entrySet()) where var1x is raw Map
        if (content.contains("class KahluaTableConverter")) {
            content = content.replace(
                    "for (Entry var5 : var1x.entrySet())",
                    "for (Entry var5 : (Iterable<Entry>)(Iterable<?>)var1x.entrySet())"
            );
            modified = true;
        }

        // CachedUrlStream/FileStream: for (LogicalOggStream var : this.logicalStreams.values())
        // logicalStreams is raw HashMap
        if (content.contains("class CachedUrlStream") || content.contains("class FileStream")) {
            content = content.replaceAll(
                    "for \\(LogicalOggStream (\\w+) : this\\.logicalStreams\\.values\\(\\)\\)",
                    "for (LogicalOggStream $1 : (Iterable<LogicalOggStream>)(Iterable<?>)this.logicalStreams.values())"
            );
            modified = true;
        }

        // LuaManager: byte4 variable declared in try-body but used after try-with-resources close.
        // The value is always 1 — inline it at the return site and remove the dead variable.
        if (content.contains("class GlobalObject") && content.contains("byte4 = 1;")) {
            content = content.replace("byte4 = 1;\n", "");
            content = content.replace("return byte4;", "return 1;");
            modified = true;
        }

        // ChooseGameInfo: int5 declared inside try block but used outside.
        // Move the declaration before the try block.
        if (content.contains("class ChooseGameInfo") && content.contains("int5 = Integer.parseInt(")) {
            content = content.replaceFirst(
                    "(String string8 = strings\\[0\\];)\n(\\s*)try \\{\n(\\s*)int5 = Integer\\.parseInt",
                    "$1\n$2int int5 = 0;\n\n$2try {\n$3int5 = Integer.parseInt"
            );
            modified = true;
        }

        // IsoMetaGrid: Object object0 = null; should be RoomDef — used as RoomDef in roomChoices.add()
        if (content.contains("class IsoMetaGrid") && content.contains("Object object0 = null;")) {
            // Only change the declaration in getRandomRoomBetweenRange (near roomChoices)
            content = content.replace(
                    "Object object0 = null;\n        float float0 = 0.0F;\n        roomChoices.clear();",
                    "RoomDef object0 = null;\n        float float0 = 0.0F;\n        roomChoices.clear();"
            );
            modified = true;
        }

        // IsoAnim: Object object = null; should be IsoDirectionFrame — used in Frames.add()
        if (content.contains("class IsoAnim")) {
            content = content.replace(
                    "Object object = null;",
                    "IsoDirectionFrame object = null;"
            );
            modified = true;
        }

        // IsoWorld.getFreeEmitter: Object object = null; should be BaseSoundEmitter
        if (content.contains("class IsoWorld") && content.contains("Object object = null;\n        if (this.freeEmitters.isEmpty())")) {
            content = content.replace(
                    "Object object = null;\n        if (this.freeEmitters.isEmpty())",
                    "BaseSoundEmitter object = null;\n        if (this.freeEmitters.isEmpty())"
            );
            modified = true;
        }

        // ChatManager.processJoinChatPacket: Object object = null; should be ChatBase
        if (content.contains("class ChatManager") && content.contains("Object object = null;\n        switch (chatType)")) {
            content = content.replace(
                    "Object object = null;\n        switch (chatType)",
                    "ChatBase object = null;\n        switch (chatType)"
            );
            modified = true;
        }

        // RenderThread: decompiler renames field "RenderThread" to "s_renderThread" to avoid
        // shadowing the class name, but lwjglx accesses it by reflection using the original name.
        // Fix both inside the class and external references (RenderThread.s_renderThread).
        if (content.contains("s_renderThread")) {
            if (content.contains("class RenderThread")) {
                // Inside the class: rename field and qualify method refs with FQN
                content = content.replace("s_renderThread", "RenderThread");
                content = content.replace("RenderThread.s_performance.", "s_performance.");
                content = content.replace("RenderThread::renderLoop", "zombie.core.opengl.RenderThread::renderLoop");
                content = content.replace("RenderThread::uncaughtException", "zombie.core.opengl.RenderThread::uncaughtException");
                content = content.replace("RenderThread::renderStep", "zombie.core.opengl.RenderThread::renderStep");
                content = content.replace("RenderThread::waitForRenderStateCallback", "zombie.core.opengl.RenderThread::waitForRenderStateCallback");
            } else {
                // External files: RenderThread.s_renderThread → RenderThread.RenderThread
                content = content.replace("RenderThread.s_renderThread", "RenderThread.RenderThread");
            }
            modified = true;
        }


        // ActionState.parseTags: Vineflower mixes up lambda captures — both "tags" and "childTags"
        // branches add to arrayList1, but "tags" should add to arrayList0.
        if (content.contains("class ActionState")) {
            content = content.replace(
                    "if (element.getNodeName().equals(\"tags\")) {\n"
                  + "            PZXmlUtil.forEachElement(element, elementx -> {\n"
                  + "                if (elementx.getNodeName().equals(\"tag\")) {\n"
                  + "                    arrayList1.add(elementx.getTextContent());",
                    "if (element.getNodeName().equals(\"tags\")) {\n"
                  + "            PZXmlUtil.forEachElement(element, elementx -> {\n"
                  + "                if (elementx.getNodeName().equals(\"tag\")) {\n"
                  + "                    arrayList0.add(elementx.getTextContent());"
            );
            modified = true;
        }

        // IsoPuddles/IsoWater renderSome: Vineflower types vertex index counter as byte instead of
        // short/int. byte byte0 = 0; byte0 += 4; truncates at 128 (byte overflow), corrupting
        // index buffer after 32 quads. The generic fixByteCounterVars now handles this via
        // improved varNeedsByte() that distinguishes ShortBuffer.put() from ByteBuffer.put().
        // These hardcoded fixes remain as safety nets in case the generic fix regresses.
        if (content.contains("class IsoPuddles") && content.contains("byte byte0 = 0;")) {
            content = content.replace(
                    "byte byte0 = 0;\n        int byte1 = 0;",
                    "short byte0 = 0;\n        int byte1 = 0;"
            );
            modified = true;
        }
        if (content.contains("class IsoWater") && content.contains("byte byte0 = 0;")) {
            content = content.replace(
                    "byte byte0 = 0;\n        int byte1 = 0;",
                    "short byte0 = 0;\n        int byte1 = 0;"
            );
            modified = true;
        }

        // ActionContext.evaluateCurrentStateTransitions: Vineflower drops the transitionOut check.
        // The bytecode doesn't show it explicitly, but the original game requires it —
        // without it, a transitionOut transition with null target spams warnings and freezes the player.
        if (content.contains("class ActionContext")) {
            content = content.replace(
                    "if (actionTransition.passes(this, 0)) {\n"
                  + "                if (StringUtils.isNullOrWhitespace(actionTransition.transitionTo)) {",
                    "if (actionTransition.passes(this, 0)) {\n"
                  + "                if (actionTransition.transitionOut) {\n"
                  + "                    break;\n"
                  + "                }\n"
                  + "\n"
                  + "                if (StringUtils.isNullOrWhitespace(actionTransition.transitionTo)) {"
            );
            modified = true;
        }

        return content;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static boolean isReassigned(String varName, String[] lines, int start, int end) {
        String vn = Pattern.quote(varName);
        for (int j = start; j < end; j++) {
            // Match: varName = expr (but not varName == or varName +=)
            if (Pattern.compile("(?<![=!<>+\\-*/&|^])\\b" + vn + "\\s*=(?!=)").matcher(lines[j]).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReassignedToDifferentType(String varName, String declaredType, String[] lines, int start, int end) {
        String vn = Pattern.quote(varName);
        for (int j = start; j < end; j++) {
            Matcher reassign = Pattern.compile("\\b" + vn + "\\s*=\\s*new (\\w+)\\(").matcher(lines[j]);
            if (reassign.find()) {
                if (!reassign.group(1).equals(declaredType)) return true;
                continue;
            }
            // Any non-null reassignment that isn't the same new Type → potentially different type
            if (Pattern.compile("(?<![=!<>+\\-*/&|^])\\b" + vn + "\\s*=\\s*(?!null|" + vn + ")").matcher(lines[j]).find()) {
                return true;
            }
        }
        return false;
    }

    private static int findScopeEnd(String[] lines, int startLine) {
        int depth = 0;
        for (int j = startLine; j < lines.length; j++) {
            depth += countChar(lines[j], '{') - countChar(lines[j], '}');
            if (depth < 0) return j;
        }
        return lines.length;
    }

    private static boolean varNeedsByte(String varName, String[] lines, int startLine, int endLine) {
        int scopeEnd = findScopeEnd(lines, startLine);
        endLine = Math.min(endLine, scopeEnd);
        String vn = Pattern.quote(varName);
        // Pattern for .put(varName) — but we need to exclude ShortBuffer/FloatBuffer/IntBuffer receivers
        Pattern putPattern = Pattern.compile("\\.(?:put|putByte|writeByte)\\(\\s*" + vn + "\\b");
        // Non-byte buffer receivers: if the .put() is on a ShortBuffer/FloatBuffer/IntBuffer,
        // the argument is not byte. Check for variable names containing "short"/"float"/"int"
        // (decompiler names them shortBuffer, floatBuffer, etc.) or declared type on same line.
        Pattern nonByteBufferPut = Pattern.compile(
                "(?:short|Short|float|Float|int|Int|Long|long)\\w*\\.(?:put|putByte|writeByte)\\(\\s*" + vn + "\\b"
        );
        Pattern[] patterns = {
                Pattern.compile("\\bbyte\\s+\\w+\\s*=\\s*" + vn + "\\b"),
                Pattern.compile("\\bbyte\\d+\\s*=\\s*" + vn + "\\b"),
                Pattern.compile("\\(byte\\)\\s*(?:\\()?\\s*" + vn + "\\b"),
                Pattern.compile("\\(byte\\)\\s*\\([^)]*\\b" + vn + "\\b"),
                Pattern.compile("\\bnew\\s+\\w+\\([^)]*\\b" + vn + "\\b"),
        };
        for (int j = startLine; j < endLine; j++) {
            String line = lines[j];
            // Check .put() calls — only count as needing byte if receiver is NOT a non-byte buffer
            if (putPattern.matcher(line).find() && !nonByteBufferPut.matcher(line).find()) {
                return true;
            }
            for (Pattern p : patterns) {
                if (p.matcher(line).find()) return true;
            }
        }
        return false;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    // ========================================================================
    // Fix: Wrong (String) cast on .get() calls
    // ========================================================================
    // Vineflower sometimes infers String type for variables assigned from
    // .get() calls (which return Object) when the variable is used in string
    // concatenation. If the variable is later used with instanceof or passed
    // to methods accepting Object (like prepareMetatableCall, getBinMetaOp),
    // the cast is wrong. This detects the pattern and widens to Object.

    private static final Pattern STRING_CAST_GET_DECL = Pattern.compile(
            "^(\\s*)String (\\w+) = \\(String\\)(\\S+\\.get\\(.+\\));$"
    );
    private static final Pattern STRING_CAST_GET_ASSIGN = Pattern.compile(
            "^(\\s*)(\\w+) = \\(String\\)(\\S+\\.get\\(.+\\));$"
    );
    private static final Pattern STRING_VAR_DECL = Pattern.compile(
            "^(\\s*)String (\\w+) = (.+);$"
    );

    // ========================================================================
    // Build 42-specific fixes
    // ========================================================================

    private static String fixB42SpecificErrors(String content) {
        // MainScreenState: Core.getInstance(.getVersion()) — stray dot before getVersion()
        if (content.contains("class MainScreenState")) {
            content = content.replace(
                    "Core.getInstance(.getVersion())",
                    "Core.getInstance().getVersion()"
            );
        }

        // ================================================================
        // 1. ICallback generics — unchecked cast needed in Invokers, Stacks,
        //    Consumers, Predicates, Comparators
        // ================================================================
        content = fixB42ICallbackGenerics(content);

        // ================================================================
        // 2. "variable already defined" — redeclared variables in same scope
        // ================================================================
        content = fixB42VariableAlreadyDefined(content);

        // ================================================================
        // 3. Enum generics — (Enum[]) needs cast to (E[]) in for-each
        // ================================================================
        content = fixB42EnumGenericsCast(content);

        // ================================================================
        // 4. byte/int to boolean confusion
        // ================================================================
        content = fixB42IntBooleanFields(content);

        // ================================================================
        // 5. File-specific fixes for remaining errors
        // ================================================================
        content = fixB42FileSpecificErrors(content);

        // Switch expressions missing default branch
        content = content.replaceAll(
                "(case \\w+ -> [^;]+;\\s*\\n)(\\s*\\} \\*)",
                "$1            default -> throw new IllegalStateException();\n$2");

        // Vineflower bug: yield before if makes code unreachable in switch expressions.
        // Pattern: yield X;\n  if (...) { ... } — the yield should be the fallback AFTER the if block.
        // General fix: find "yield EXPR;\n<ws>if (" and move the yield after the closing }.
        content = fixYieldBeforeIf(content);

        // AnimalZoneState: ZoneState is private but referenced by public STATE field
        if (content.contains("class AnimalZoneState")) {
            content = content.replace(
                    "private abstract static class ZoneState",
                    "public abstract static class ZoneState");
        }

        // Unreachable catch: CloneNotSupportedException never thrown by super.clone()
        // when the class implements Cloneable. Widen to Exception.
        content = content.replace(
                "catch (CloneNotSupportedException",
                "catch (Exception");

        return content;
    }

    // ========================================================================
    // B42 Helper: ICallback generics — add unchecked cast on assignment
    // ========================================================================
    // In Invokers, Stacks, Consumers, Predicates, Comparators the pattern is:
    //   item.fieldName = paramName;
    // where item is Pool<...<Object,...>> and paramName has generic type params.
    // Fix: item.fieldName = (ICallback)paramName; (raw cast suppresses generics)

    private static String fixB42ICallbackGenerics(String content) {
        if (!content.contains("class Invokers")
                && !content.contains("class Stacks")
                && !content.contains("class Consumers")
                && !content.contains("class Predicates")
                && !content.contains("class Comparators")) {
            return content;
        }

        // Pattern: item.invoker = consumer;  ->  item.invoker = (ICallback)consumer;
        // Pattern: item.callback = callback;  ->  item.callback = (ICallback)callback;
        // Pattern: item.consumer = consumer;  ->  item.consumer = (ICallback)consumer;
        // Pattern: item.predicate = predicate;  ->  item.predicate = (ICallback)predicate;
        // Pattern: item.comparator = comparator;  ->  item.comparator = (ICallback)comparator;
        content = content.replaceAll(
                "(\\s*item\\.(invoker|callback|consumer|predicate|comparator)) = (\\w+);",
                "$1 = (ICallback)$3;"
        );

        return content;
    }

    // ========================================================================
    // B42 Helper: Fix "variable already defined" errors
    // ========================================================================
    // When Vineflower decompiles switch-case or large methods, it redeclares
    // variables. Fix by removing the type from subsequent declarations.

    private static String fixB42VariableAlreadyDefined(String content) {
        // IsoFeedingTrough: instanceof pattern var 'food' conflicts with earlier 'food'
        if (content.contains("class IsoFeedingTrough")) {
            // Rename pattern var and its use within the same line's block
            content = content.replace(
                    "item instanceof DrainableComboItem food)",
                    "item instanceof DrainableComboItem drainableFood)"
            );
            content = content.replace("result += food.getCurrentUses()", "result += drainableFood.getCurrentUses()");
        }

        // BaseCraftingLogic bestMatch redeclaration is fixed in fixB42FileSpecificErrors.

        // AddCoopPlayer: cellx and chunkMap redeclared in update()
        if (content.contains("class AddCoopPlayer")) {
            content = fixB42RemoveRedeclType(content,
                    "IsoCell cellx = IsoWorld.instance.currentCell;",
                    "cellx = IsoWorld.instance.currentCell;",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "IsoChunkMap chunkMap = cellx.chunkMap[this.player.playerIndex];",
                    "chunkMap = cellx.chunkMap[this.player.playerIndex];",
                    1);
        }

        // ChecksumPacket: bbw redeclared in parseServer() — 2nd and 3rd occurrences
        if (content.contains("class ChecksumPacket")) {
            content = fixB42RemoveRedeclType(content,
                    "ByteBufferWriter bbw = connection.startPacket();",
                    "bbw = connection.startPacket();",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "ByteBufferWriter bbw = connection.startPacket();",
                    "bbw = connection.startPacket();",
                    2);
        }

        // CombatManager: lower redeclared in getBodyPart()
        if (content.contains("class CombatManager")) {
            content = fixB42RemoveRedeclType(content,
                    "boolean lower = Rand.Next(2) == 0;",
                    "lower = Rand.Next(2) == 0;",
                    2);
        }

        // VehicleScript shape redeclaration is fixed in fixB42FileSpecificErrors.

        return content;
    }

    /**
     * Remove the type from the Nth occurrence of a variable declaration.
     * @param content source
     * @param fullDecl the full declaration to find (e.g. "int x = 5;")
     * @param assignment the assignment without type (e.g. "x = 5;")
     * @param occurrence which occurrence to fix (1-based; 2 = second occurrence)
     */
    private static String fixB42RemoveRedeclType(String content, String fullDecl, String assignment, int occurrence) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(fullDecl, idx)) >= 0) {
            count++;
            if (count == occurrence) {
                content = content.substring(0, idx)
                        + assignment
                        + content.substring(idx + fullDecl.length());
                break;
            }
            idx += fullDecl.length();
        }
        return content;
    }

    /**
     * Rename the Nth occurrence of an instanceof pattern variable and all uses
     * within its if-block scope.
     */
    private static String fixB42RenameInstanceofPatternVar(String content,
            String instanceofExpr, String oldVar, String newVar, int occurrence) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(instanceofExpr, idx)) >= 0) {
            count++;
            if (count == occurrence) {
                // Replace the pattern variable in the instanceof expression
                String newExpr = instanceofExpr.replace(" " + oldVar, " " + newVar);
                content = content.substring(0, idx) + newExpr + content.substring(idx + instanceofExpr.length());

                // Find the scope (the if-block following this instanceof)
                int braceStart = content.indexOf("{", idx + newExpr.length());
                if (braceStart >= 0) {
                    int braceDepth = 1;
                    int scopeEnd = braceStart + 1;
                    while (scopeEnd < content.length() && braceDepth > 0) {
                        char c = content.charAt(scopeEnd);
                        if (c == '{') braceDepth++;
                        else if (c == '}') braceDepth--;
                        scopeEnd++;
                    }
                    // Replace all occurrences of oldVar within this scope
                    String scopeContent = content.substring(braceStart, scopeEnd);
                    scopeContent = scopeContent.replaceAll("\\b" + Pattern.quote(oldVar) + "\\b", newVar);
                    content = content.substring(0, braceStart) + scopeContent + content.substring(scopeEnd);
                }
                break;
            }
            idx += instanceofExpr.length();
        }
        return content;
    }

    /**
     * Rename an instanceof pattern variable and its uses within the immediately
     * following scope. Used when a pattern var like 'food' conflicts with an
     * earlier variable of the same name.
     */
    private static String fixB42RenamePatternVarInScope(String content,
            String afterExpr, String newVar, String usageToReplace) {
        int idx = content.indexOf(afterExpr);
        if (idx < 0) return content;

        // Find the scope block
        int blockStart = content.indexOf("{", idx);
        if (blockStart < 0) return content;

        // The next line after the instanceof check uses the new variable
        int blockEnd = blockStart + 1;
        int depth = 1;
        while (blockEnd < content.length() && depth > 0) {
            char c = content.charAt(blockEnd);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            blockEnd++;
        }

        // Within this scope, replace the original usageToReplace with newVar
        // but ONLY standalone word boundaries
        String scope = content.substring(blockStart, blockEnd);
        // The pattern var is already renamed. We need to fix any reference to old name
        // Actually in this case the uses after the pattern match already use the old name
        // 'food' -- we need to replace them with 'food2'
        scope = scope.replaceAll("\\b" + Pattern.quote(usageToReplace) + "\\b", newVar);
        content = content.substring(0, blockStart) + scope + content.substring(blockEnd);

        return content;
    }

    /**
     * Fix for-loop variable redeclarations in a method.
     * When a for(int x = ...) appears multiple times in the same method,
     * subsequent ones need the type removed.
     */
    private static String fixB42RedeclaredForLoopVars(String content, String methodName) {
        String[] lines = content.split("\n", -1);
        boolean inMethod = false;
        Set<String> declaredForVars = new HashSet<>();
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            // Detect method start (simplified)
            if (trimmed.contains(methodName + "(")) {
                inMethod = true;
                declaredForVars.clear();
                continue;
            }

            if (!inMethod) continue;

            // Detect method end at depth 0 (simplified: track brace depth)
            // For simplicity, just process for-loop patterns
            Matcher forM = Pattern.compile("for \\(int (\\w+) = ").matcher(trimmed);
            if (forM.find()) {
                String varName = forM.group(1);
                if (declaredForVars.contains(varName)) {
                    // Remove the type declaration: "for (int x = " -> "for (x = "
                    lines[i] = lines[i].replace("for (int " + varName + " = ", "for (" + varName + " = ");
                    modified = true;
                } else {
                    declaredForVars.add(varName);
                }
            }
        }

        return modified ? String.join("\n", lines) : content;
    }

    /**
     * Fix block-scoped variable redeclarations (like ModelAttachment modelAttachment).
     * When the same variable is declared multiple times in the same method,
     * remove the type from subsequent declarations.
     */
    private static String fixB42RedeclaredBlockVars(String content, String declPrefix) {
        // Find all occurrences and remove the type from 2nd+ in the same method
        String[] parts = content.split("\n", -1);
        Map<String, Integer> methodDeclCounts = new HashMap<>();
        String currentMethod = "";
        boolean modified = false;

        for (int i = 0; i < parts.length; i++) {
            String trimmed = parts[i].trim();
            // Detect method declarations
            Matcher methodM = Pattern.compile("^(?:public|private|protected|static|final|synchronized|\\s)*\\s+\\w+\\s+(\\w+)\\s*\\(").matcher(trimmed);
            if (methodM.find() && trimmed.contains("(") && !trimmed.startsWith("new ") && !trimmed.startsWith("return ")) {
                currentMethod = methodM.group(1);
                methodDeclCounts.clear();
            }

            if (trimmed.startsWith(declPrefix)) {
                String key = currentMethod + ":" + declPrefix;
                int count = methodDeclCounts.getOrDefault(key, 0) + 1;
                methodDeclCounts.put(key, count);
                if (count > 1) {
                    // Remove the type: "ModelAttachment modelAttachment = ..." -> "modelAttachment = ..."
                    String varName = declPrefix.substring(declPrefix.lastIndexOf(' ') + 1);
                    int typeStart = parts[i].indexOf(declPrefix);
                    int varStart = typeStart + declPrefix.length() - varName.length();
                    parts[i] = parts[i].substring(0, typeStart) + parts[i].substring(varStart);
                    modified = true;
                }
            }
        }

        return modified ? String.join("\n", parts) : content;
    }

    // ========================================================================
    // B42 Helper: Fix Enum generics cast — (Enum[]) to (E[]) with unchecked
    // ========================================================================

    private static String fixB42EnumGenericsCast(String content) {
        // Pattern: for (E e : (Enum[])this.elementType.getEnumConstants())
        // Fix: cast to E[] instead: for (E e : (E[])this.elementType.getEnumConstants())
        // This pattern appears in EnumBitStore, AttributeType, PZArrayUtil

        // General pattern: (Enum[])expr.getEnumConstants() -> (E[])expr.getEnumConstants()
        // where E is the for-each loop variable type
        // More specifically: for (SomeType var : (Enum[])... -> for (SomeType var : (SomeType[])...
        // and: for (SomeType var : (java.lang.Enum[])... -> for (SomeType var : (SomeType[])...
        content = content.replaceAll(
                "for \\((\\w+) (\\w+) : \\((?:java\\.lang\\.)?Enum\\[\\]\\)",
                "for ($1 $2 : ($1[])"
        );

        // EnumBitStore inner class: EnumBitStoreIterator has its own E that shadows outer E
        // Fix the inner class to not redeclare E
        if (content.contains("class EnumBitStore")) {
            // Fix: private class EnumBitStoreIterator<E extends Enum<E> & IOEnum>
            // -> private class EnumBitStoreIterator (remove the type parameter)
            content = content.replace(
                    "private class EnumBitStoreIterator<E extends Enum<E> & IOEnum> implements Iterator<E>",
                    "private class EnumBitStoreIterator implements Iterator<E>"
            );

            // Fix: EnumBitStore<E>.EnumBitStoreIterator<E> -> EnumBitStoreIterator
            content = content.replaceAll(
                    "EnumBitStore<E>\\.EnumBitStoreIterator<E>",
                    "EnumBitStoreIterator"
            );
            // Fix: new EnumBitStore.EnumBitStoreIterator<>() -> new EnumBitStoreIterator()
            content = content.replace(
                    "new EnumBitStore.EnumBitStoreIterator<>()",
                    "new EnumBitStoreIterator()"
            );
        }

        // EnumStringObj: "!(o instanceof EnumStringObj<E> other)" uses reifiable generic
        // Fix: remove the generic parameter from the instanceof
        if (content.contains("class EnumStringObj")) {
            content = content.replace(
                    "!(o instanceof EnumStringObj<E> other)",
                    "!(o instanceof EnumStringObj<?> other)"
            );
        }

        return content;
    }

    // ========================================================================
    // B42 Helper: Fix int/byte assigned to boolean fields
    // ========================================================================

    private static String fixB42IntBooleanFields(String content) {
        // IsoObject: isOutlineHighlight, isOutlineHlAttached, isOutlineHlBlink are byte fields
        // but used in boolean return contexts. The methods return boolean but field is byte.
        // Fix: add != 0 comparison
        if (content.contains("class IsoObject ")) {
            // return this.isOutlineHighlight;  ->  return this.isOutlineHighlight != 0;
            content = content.replaceAll(
                    "return this\\.(isOutlineHighlight|isOutlineHlAttached|isOutlineHlBlink);",
                    "return this.$1 != 0;"
            );
            // if (this.isOutlineHighlight) {  ->  if (this.isOutlineHighlight != 0) {
            content = content.replaceAll(
                    "if \\(this\\.(isOutlineHighlight|isOutlineHlAttached|isOutlineHlBlink)\\)",
                    "if (this.$1 != 0)"
            );
        }

        // VisibilityGraph: f.n = 1; should be f.n = true; (ClusterOutline fields are boolean)
        if (content.contains("class VisibilityGraph")) {
            content = content.replace("f.n = 1;", "f.n = true;");
        }

        // ClusterOutlineGrid: f1.w = 1; should be f1.w = true;
        if (content.contains("class ClusterOutlineGrid")) {
            content = content.replace("f1.w = 1;", "f1.w = true;");
        }

        // FileSystemImpl: priority = (boolean)(16 - this.inProgress.size());
        // should be priority = 16 - this.inProgress.size(); with int type
        if (content.contains("class FileSystemImpl")) {
            content = content.replace(
                    "priority = (boolean)(16 - this.inProgress.size());",
                    "priority = 16 - this.inProgress.size();"
            );
            // Also fix the declaration: boolean priority -> int priority
            content = content.replaceAll(
                    "(\\s*)boolean priority(\\s*[;=])",
                    "$1int priority$2"
            );
            // Fix boolean literal initial value
            content = content.replace("int priority = true;", "int priority = 1;");
            content = content.replace("int priority = false;", "int priority = 0;");
        }

        // IsoWorld: isPlayerAlive = (boolean)PZMath.fastfloor(...)
        //           bLoadCharacter = (boolean)PZMath.fastfloor(...)
        // These should be int assignments. The variables are declared as boolean
        // but actually hold int values (floor coordinates).
        if (content.contains("class IsoWorld ")) {
            content = content.replace(
                    "isPlayerAlive = (boolean)PZMath.fastfloor(IsoPlayer.getInstance().getX());",
                    "isPlayerAlive = PZMath.fastfloor(IsoPlayer.getInstance().getX());"
            );
            content = content.replace(
                    "bLoadCharacter = (boolean)PZMath.fastfloor(IsoPlayer.getInstance().getY());",
                    "bLoadCharacter = PZMath.fastfloor(IsoPlayer.getInstance().getY());"
            );
            // Fix the variable types from boolean to int
            content = content.replaceAll(
                    "(\\s*)boolean isPlayerAlive(\\s*[;=])",
                    "$1int isPlayerAlive$2"
            );
            content = content.replaceAll(
                    "(\\s*)boolean bLoadCharacter(\\s*[;=])",
                    "$1int bLoadCharacter$2"
            );
            // Fix boolean literal assignments that should now be int
            content = content.replace("int isPlayerAlive = false;", "int isPlayerAlive = 0;");
            content = content.replace("isPlayerAlive = true;", "isPlayerAlive = 1;");
            // PlayerDBHelper.isPlayerAlive returns boolean, need ternary to convert to int
            content = content.replace(
                    "isPlayerAlive = PlayerDBHelper.isPlayerAlive(ZomboidFileSystem.instance.getCurrentSaveDir(), 1);",
                    "isPlayerAlive = PlayerDBHelper.isPlayerAlive(ZomboidFileSystem.instance.getCurrentSaveDir(), 1) ? 1 : 0;"
            );
            content = content.replace("int bLoadCharacter = false;", "int bLoadCharacter = 0;");
            content = content.replace("bLoadCharacter = true;", "bLoadCharacter = 1;");
            content = content.replace("bLoadCharacter = false;", "bLoadCharacter = 0;");
            // Fix boolean usage in if conditions
            content = content.replace("if (isPlayerAlive)", "if (isPlayerAlive != 0)");
            content = content.replace("if (bLoadCharacter ", "if (bLoadCharacter != 0 ");
        }

        return content;
    }

    // ========================================================================
    // B42 Helper: File-specific fixes for remaining errors
    // ========================================================================

    private static String fixB42FileSpecificErrors(String content) {
        // UIElement: rawget("Type") returns Object, needs (String) cast
        if (content.contains("class UIElement ")) {
            content = content.replace(
                    "String type = this.table.rawget(\"Type\");",
                    "String type = (String)this.table.rawget(\"Type\");"
            );
        }

        // ImagePyramid: Comparator.comparingInt with raw lambda cast to Object
        // ((Object)o).requestNumber -> ((ImagePyramid.PyramidTexture)o).requestNumber
        if (content.contains("class ImagePyramid")) {
            content = content.replace(
                    "((Object)o).requestNumber",
                    "((ImagePyramid.PyramidTexture)o).requestNumber"
            );
        }

        // ShaderBufferData: same pattern with offset field
        if (content.contains("class ShaderBufferData")) {
            content = content.replace(
                    "((Object)a).offset",
                    "((ShaderParameter)a).offset"
            );
        }

        // FBORenderCell: raw lambda comparators accessing fields on Object
        if (content.contains("class FBORenderCell")) {
            // World inventory objects sort: o1.xoff, o1.yoff on IsoWorldInventoryObject
            content = content.replaceAll(
                    "(o[12])\\.xoff",
                    "((IsoWorldInventoryObject)$1).xoff"
            );
            content = content.replaceAll(
                    "(o[12])\\.yoff",
                    "((IsoWorldInventoryObject)$1).yoff"
            );
            // lightingUpdateCounter sort
            content = content.replace(
                    "((Object)a).lightingUpdateCounter",
                    "((IsoChunk)a).lightingUpdateCounter"
            );
            // Grid square sort: o1.x, o1.y, o2.x, o2.y on IsoGridSquare
            // Need to be careful: only match in the sort lambda context
            content = content.replaceAll(
                    "(int i[12] = )(o[12])\\.x \\+ (o[12])\\.y",
                    "$1((IsoGridSquare)$2).x + ((IsoGridSquare)$3).y"
            );
        }

        // FBORenderObjectPicker: raw lambda comparator accessing .square on ClickObject
        if (content.contains("class FBORenderObjectPicker")) {
            content = content.replaceAll(
                    "(o[12])\\.square\\.z",
                    "((IsoObjectPicker.ClickObject)$1).square.z"
            );
            content = content.replace(
                    "compareRenderLayer(o1, o2)",
                    "compareRenderLayer((IsoObjectPicker.ClickObject)o1, (IsoObjectPicker.ClickObject)o2)"
            );
            content = content.replace(
                    "compareSquare(o1, o2)",
                    "compareSquare((IsoObjectPicker.ClickObject)o1, (IsoObjectPicker.ClickObject)o2)"
            );
        }

        // AnimalZone, IsoMannequin (MannequinZone), VehicleZone:
        // (String)s.rawget("...") — variable 's' not in scope, should be 'properties'
        if (content.contains("class AnimalZone")) {
            content = content.replace(
                    "(String)s.rawget(\"AnimalType\")",
                    "(String)properties.rawget(\"AnimalType\")"
            );
        }

        if (content.contains("class IsoMannequin")) {
            content = content.replace("(String)s.rawget(\"Direction\")", "(String)properties.rawget(\"Direction\")");
            content = content.replace("(String)s.rawget(\"Outfit\")", "(String)properties.rawget(\"Outfit\")");
            content = content.replace("(String)s.rawget(\"Script\")", "(String)properties.rawget(\"Script\")");
            content = content.replace("(String)s.rawget(\"Skin\")", "(String)properties.rawget(\"Skin\")");
            content = content.replace("(String)s.rawget(\"Pose\")", "(String)properties.rawget(\"Pose\")");
        }

        if (content.contains("class VehicleZone")) {
            content = content.replace(
                    "(String)s.rawget(\"Direction\")",
                    "(String)properties.rawget(\"Direction\")"
            );
        }

        // StateMachine: lambda parameter subState inferred as Object, needs cast
        if (content.contains("class StateMachine")) {
            content = content.replace(
                    "(subState, lOwner, lLayer, lTrack, lEvent) -> {\n            if (!subState.isEmpty()) {\n                subState.state.animEvent",
                    "(subState, lOwner, lLayer, lTrack, lEvent) -> {\n            if (!((StateMachine.SubstateSlot)subState).isEmpty()) {\n                ((StateMachine.SubstateSlot)subState).state.animEvent"
            );
        }

        // Pool: Pool<PO> cannot be converted to Pool<IPooledObject>
        // Fix: add raw cast
        if (content.contains("class Pool ") || content.contains("class Pool<")) {
            content = content.replace(
                    "newObj.setPool(new Pool.PoolReference(this, poolStacks));",
                    "newObj.setPool(new Pool.PoolReference((Pool)this, poolStacks));"
            );
        }

        // ClothingWetness and ClothingWetnessSync: 'clothing' instanceof pattern var
        // escapes its scope (used after while loop). These are handled by the shared
        // fixInstanceofPatternScope transform. No additional b42-specific fix needed
        // as the pattern is too complex for simple string replacement.

        // HandWeapon: text is String but later cast to (Double)text
        // The rawget returns Object, not String. Fix the specific lines.
        if (content.contains("class HandWeapon ")) {
            content = content.replace(
                    "f = (float)((Double)text).doubleValue();",
                    "f = (float)((Double)(Object)text).doubleValue();"
            );
        }

        // IsoAnimal: @Override on methods that don't override parent
        if (content.contains("class IsoAnimal ")) {
            content = content.replace(
                    "@Override\n    public void updateStress()",
                    "public void updateStress()"
            );
            content = content.replace(
                    "@Override\n    public void initializeStates()",
                    "public void initializeStates()"
            );
        }

        // TileGeometryFile: (String)block.getValue("points") — Value not String
        if (content.contains("class TileGeometryFile")) {
            content = content.replace(
                    "value = (String)block.getValue(\"points\");",
                    "value = block.getValue(\"points\");"
            );
        }

        // LoadingQueueUI: 0.4F passed where Double expected in DrawTextureScaledColor
        if (content.contains("class LoadingQueueUI")) {
            content = content.replace("0.4F, 0.4F, 0.4F, 1.0);", "(double)0.4F, (double)0.4F, (double)0.4F, 1.0);");
        }

        // RecipeCodeOnCreate: getConsumedItems/getInputItems/getCreatedItems return List<InventoryItem>
        // but assigned to subtype. Need casts.
        if (content.contains("class RecipeCodeOnCreate")) {
            content = content.replace(
                    "Food head = getConsumedItems(data, ItemTag.ANIMAL_HEAD).getFirst();",
                    "Food head = (Food)getConsumedItems(data, ItemTag.ANIMAL_HEAD).getFirst();"
            );
            content = content.replace(
                    "Clothing item = getConsumedItems(data, ItemTag.PICK_ARAMID_THREAD).getFirst();",
                    "Clothing item = (Clothing)getConsumedItems(data, ItemTag.PICK_ARAMID_THREAD).getFirst();"
            );
            content = content.replace(
                    "Key sourceKey = getInputItems(data, ItemTag.BUILDING_KEY).getFirst();",
                    "Key sourceKey = (Key)getInputItems(data, ItemTag.BUILDING_KEY).getFirst();"
            );
            content = content.replace(
                    "Food macaroni = getCreatedItems(data, ItemTag.PASTA).getFirst();",
                    "Food macaroni = (Food)getCreatedItems(data, ItemTag.PASTA).getFirst();"
            );
            // Multi-line getConsumedItems with ItemKey params returning DrainableComboItem
            content = content.replace(
                    "DrainableComboItem lantern = getConsumedItems(",
                    "DrainableComboItem lantern = (DrainableComboItem)getConsumedItems("
            );
        }

        // EditVehicleState: Collectors.joining returns Object instead of String
        // .collect(Collectors.joining(", ")) returns Object because stream is raw
        if (content.contains("class EditVehicleState")) {
            content = content.replace(
                    "String collect = var10000.<CharSequence>map(xva$0 -> \"%s\".formatted(xva$0)).collect(Collectors.joining(\", \"));",
                    "String collect = (String)var10000.<CharSequence>map(xva$0 -> \"%s\".formatted(xva$0)).collect(Collectors.joining(\", \"));"
            );
        }

        // PrimitiveFloatList: forEach ambiguity — method ref action::accept is ambiguous
        if (content.contains("class PrimitiveFloatList")) {
            content = content.replace(
                    "this.forEach(action::accept);",
                    "this.forEach((FloatConsumer)action::accept);"
            );
        }

        // ZombieDeleteOnClientPacket: raw ArrayList cast needs type in for-each
        if (content.contains("class ZombieDeleteOnClientPacket")) {
            content = content.replace(
                    "for (NetworkZombiePacker.DeletedZombie dz : (ArrayList)values[1])",
                    "for (NetworkZombiePacker.DeletedZombie dz : (ArrayList<NetworkZombiePacker.DeletedZombie>)values[1])"
            );
        }

        // ClothingWetnessPacket: raw List cast needs type in for-each
        if (content.contains("class ClothingWetnessPacket")) {
            content = content.replace(
                    "for (InventoryItem item : (List)values[1])",
                    "for (InventoryItem item : (List<InventoryItem>)values[1])"
            );
        }

        // RemoveInventoryItemFromContainerPacket: raw ArrayList cast
        if (content.contains("class RemoveInventoryItemFromContainerPacket")) {
            content = content.replace(
                    "for (InventoryItem item : (ArrayList)values[1])",
                    "for (InventoryItem item : (ArrayList<InventoryItem>)values[1])"
            );
        }

        // GameServer: (String)ServerOptions.instance.isPublic.getValue() — getValue returns Boolean
        if (content.contains("class GameServer ")) {
            content = content.replace(
                    "String tags = (String)ServerOptions.instance.isPublic.getValue() ? \"\" : \"hidden\";",
                    "String tags = ServerOptions.instance.isPublic.getValue() ? \"\" : \"hidden\";"
            );
        }

        // IsoLot: ambiguous get() call — remove Integer.valueOf wrapper
        if (content.contains("class IsoLot ")) {
            content = content.replace(
                    "return get(mapFiles, cX, cY, wX, Integer.valueOf(wY), ch);",
                    "return get(mapFiles, Integer.valueOf(cX), Integer.valueOf(cY), Integer.valueOf(wX), Integer.valueOf(wY), ch);"
            );
        }

        // Select: QuickSelect<?> wildcard causes T[] incompatibility
        if (content.contains("class Select ")) {
            content = content.replace(
                    "private QuickSelect<?> quickSelect;",
                    "private QuickSelect quickSelect;"
            );
        }

        // XuiScript: (XuiScript.XuiVar<T, C>) uses undefined T, C
        if (content.contains("class XuiScript")) {
            content = content.replace(
                    "(XuiScript.XuiVar<T, C>)style.getVar(",
                    "(XuiScript.XuiVar)style.getVar("
            );
            content = content.replace(
                    "(XuiScript.XuiVar<T, C>)defaultStyle.getVar(",
                    "(XuiScript.XuiVar)defaultStyle.getVar("
            );
        }

        // FirearmPanel: stream collect inference — toCollection(ArrayList::new) can't infer
        // generic types because the stream element is Object. Cast the map result.
        if (content.contains("class FirearmPanel")) {
            content = content.replace(
                    ".map(i -> InventoryItemFactory.CreateItem(i.getFullName()))",
                    ".<WeaponPart>map(i -> (WeaponPart)InventoryItemFactory.CreateItem(i.getFullName()))"
            );
        }

        // AnimalZones: subState.getClass() on inaccessible type
        if (content.contains("class AnimalZones")) {
            content = content.replace(
                    "subState.getClass().getSimpleName()",
                    "((Object)subState).getClass().getSimpleName()"
            );
        }

        // PZArrayUtil enum generic cast is handled by the general fixB42EnumGenericsCast regex

        // ================================================================
        // UI3DScene: many "variable already defined" errors across fromLua methods
        // ================================================================
        if (content.contains("class UI3DScene ")) {
            // fromLua0: ArrayList<String> names redeclared in "getObjectNames" case
            // First is in "getGeometryNames", second in "getObjectNames" — same switch scope
            content = fixB42RemoveRedeclType(content,
                    "ArrayList<String> names = new ArrayList<>();",
                    "names = new ArrayList<>();",
                    2);

            // fromLua2: int i = 0; at line 992, then for (int i = 0; ...) at line 1085
            // The for-loop redeclares 'i' — remove the type from the for-loop
            content = content.replace(
                    "for (int i = 0; i < keyframes.length; i++)",
                    "for (i = 0; i < keyframes.length; i++)");

            // fromLua2: sceneCharacter instanceof pattern at line 1353 conflicts with
            // earlier SceneCharacter sceneCharacter declaration at line 1064.
            // Rename the instanceof pattern variable to _sceneCharacter.
            content = fixB42RenameInstanceofPatternVar(content,
                    "sceneObject instanceof UI3DScene.SceneCharacter sceneCharacter",
                    "sceneCharacter", "_sceneCharacter", 1);

            // fromLua2: modID, tileName, sprite, spriteGrid, spriteGridIndex redeclared
            // in "subtractSpriteGridPixels" case — first declarations are in "copyGeometryFromSpriteGrid"
            content = fixB42RemoveRedeclType(content,
                    "String modID = (String)arg0;",
                    "modID = (String)arg0;",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "String tileName = (String)arg1;",
                    "tileName = (String)arg1;",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "IsoSprite sprite = IsoSpriteManager.instance.getSprite(tileName);",
                    "sprite = IsoSpriteManager.instance.getSprite(tileName);",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "IsoSpriteGrid spriteGrid = sprite.getSpriteGrid();",
                    "spriteGrid = sprite.getSpriteGrid();",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "int spriteGridIndex = spriteGrid.getSpriteIndex(sprite);",
                    "spriteGridIndex = spriteGrid.getSpriteIndex(sprite);",
                    2);

            // fromLua3: Matrix4f transform redeclared in "setAttachmentToOrigin" case
            // String is unique in the file — occurrence=1 targets the only match
            content = fixB42RemoveRedeclType(content,
                    "Matrix4f transform = sceneModel.getGlobalTransform(allocMatrix4f());",
                    "transform = sceneModel.getGlobalTransform(allocMatrix4f());",
                    1);
            // fromLua3: Quaternionf rotation redeclared — identical string at lines 1585 and 1628
            content = fixB42RemoveRedeclType(content,
                    "Quaternionf rotation = transform.getUnnormalizedRotation(allocQuaternionf());",
                    "rotation = transform.getUnnormalizedRotation(allocQuaternionf());",
                    2);

            // fromLua3: sceneModel instanceof pattern at line 1655 conflicts with
            // SceneModel sceneModel declaration at line 1611.
            // Convert instanceof pattern to instanceof + cast to avoid field name
            // collision (this.originBone.sceneModel shares the name).
            content = content.replace(
                    "if (sceneObject instanceof UI3DScene.SceneModel sceneModel) {\n"
                            + "                            this.gizmoParent = sceneModel;\n"
                            + "                            this.originBone.character = null;\n"
                            + "                            this.originBone.sceneModel = sceneModel;\n"
                            + "                            this.originBone.boneName = (String)arg2;\n"
                            + "                            this.gizmoOrigin = this.originBone;\n"
                            + "                            this.gizmoChild = null;\n"
                            + "                        }",
                    "if (sceneObject instanceof UI3DScene.SceneModel) {\n"
                            + "                            UI3DScene.SceneModel sceneModel2 = (UI3DScene.SceneModel)sceneObject;\n"
                            + "                            this.gizmoParent = sceneModel2;\n"
                            + "                            this.originBone.character = null;\n"
                            + "                            this.originBone.sceneModel = sceneModel2;\n"
                            + "                            this.originBone.boneName = (String)arg2;\n"
                            + "                            this.gizmoOrigin = this.originBone;\n"
                            + "                            this.gizmoChild = null;\n"
                            + "                        }");

            // fromLua6: byte col = -1 conflicts with int col earlier in same switch
            // Only one occurrence of "byte col = -1;" — replace directly
            content = content.replace("byte col = -1;", "col = -1;");
        }

        // ================================================================
        // ModelLoader: 6 for-loop redeclarations of 'n' in loadTxt()
        // The first 'n' is declared as standalone "int n = 0;" (VertexBuffer case),
        // then 6 for-loops in subsequent switch cases all redeclare 'int n'.
        // Replace ALL "for (int n = " with "for (n = " in this file since every
        // for-loop 'n' conflicts with the standalone declaration.
        // ================================================================
        if (content.contains("class ModelLoader")) {
            content = content.replace("for (int n = 0; n < numElements; n++)",
                    "for (n = 0; n < numElements; n++)");
            content = content.replace("for (int n = 0; n < numBones; n++)",
                    "for (n = 0; n < numBones; n++)");
            content = content.replace("for (int n = 0; n < nFrames; n++)",
                    "for (n = 0; n < nFrames; n++)");
        }

        // ================================================================
        // IsoMannequin: String i conflicts with for (int i) in syncModel()
        // ================================================================
        if (content.contains("class IsoMannequin")) {
            // Rename "String i = this.modelScriptName;" to avoid conflict with loop var i
            content = content.replace(
                    "String i = this.modelScriptName;\n        switch (i)",
                    "String modelScriptSwitch = this.modelScriptName;\n        switch (modelScriptSwitch)");
        }

        // ================================================================
        // SpriteModel: byte modelAttachment conflicts with ModelAttachment modelAttachment
        // in both parseStandardDoor() and parsePairDoor()
        // ================================================================
        if (content.contains("class SpriteModel")) {
            // Rename the byte switch variable to edgeIndex in both methods.
            // The byte variable is used for edge direction (n=1, w=0) then
            // conflicts with ModelAttachment modelAttachment later.
            content = content.replace("byte modelAttachment = -1;", "byte edgeIndex = -1;");
            content = content.replace("modelAttachment = 1;", "edgeIndex = 1;");
            content = content.replace("modelAttachment = 0;", "edgeIndex = 0;");
            content = content.replace(
                    "String meshName = switch (modelAttachment)",
                    "String meshName = switch (edgeIndex)");
        }

        // ================================================================
        // VehicleScript: String shape conflicts with PhysicsShape shape in LoadPhysicsShape()
        // ================================================================
        if (content.contains("class VehicleScript ")) {
            // String shape is only used in declaration and switch expression
            content = content.replace(
                    "String shape = block.id;\n        int type;\n        switch (shape)",
                    "String shapeName = block.id;\n        int type;\n        switch (shapeName)");
        }

        // ================================================================
        // BaseCraftingLogic: bestMatch redeclared in switch case OutputName
        // (3rd overall occurrence; first is in Tags block, 2nd in InputName, 3rd in OutputName)
        // InputName and OutputName are in the same switch scope so 3rd conflicts with 2nd.
        // ================================================================
        if (content.contains("class BaseCraftingLogic")) {
            content = fixB42RemoveRedeclType(content,
                    "BaseCraftingLogic.FilterStringMatchType bestMatch = BaseCraftingLogic.FilterStringMatchType.NONE;",
                    "bestMatch = BaseCraftingLogic.FilterStringMatchType.NONE;",
                    3);
        }

        // ================================================================
        // ClothingWetness: clothing instanceof pattern variable escapes scope
        // 'clothing' is declared in "if (item instanceof Clothing clothing)" but
        // used outside that if-block. Replace escaped usage with cast on 'item'.
        // ================================================================
        if (content.contains("class ClothingWetness ")) {
            content = content.replace(
                    "clothing.setWetness(clothing.getWetness() + delta);",
                    "((Clothing)item).setWetness(((Clothing)item).getWetness() + delta);");
        }

        return content;
    }

    private static String fixWrongStringCastOnGet(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        // Collect all variables that have a (String)x.get() assignment (decl or reassign)
        // and check if they need widening to Object.
        Set<String> varsWithCastGet = new HashSet<>();
        Map<String, Integer> stringDeclLines = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            // Track String variable declarations
            Matcher declM = STRING_VAR_DECL.matcher(lines[i]);
            if (declM.matches()) {
                stringDeclLines.put(declM.group(2), i);
            }
            // Track any (String)x.get() assignment (declaration or reassignment)
            Matcher castDeclM = STRING_CAST_GET_DECL.matcher(lines[i]);
            if (castDeclM.matches()) {
                varsWithCastGet.add(castDeclM.group(2));
                continue;
            }
            Matcher castAssignM = STRING_CAST_GET_ASSIGN.matcher(lines[i]);
            if (castAssignM.matches() && stringDeclLines.containsKey(castAssignM.group(2))) {
                varsWithCastGet.add(castAssignM.group(2));
            }
        }

        // Determine which variables actually need widening by checking usage
        Set<String> varsToWiden = new HashSet<>();
        for (String varName : varsWithCastGet) {
            Integer declLine = stringDeclLines.get(varName);
            if (declLine == null) continue;
            int scopeEnd = Math.min(findScopeEnd(lines, declLine + 1), lines.length);
            for (int j = declLine + 1; j < scopeEnd; j++) {
                String line = lines[j].stripLeading();
                if (line.contains(varName + " instanceof ")
                        || line.contains("prepareMetatableCall(" + varName)
                        || line.contains("getBinMetaOp(" + varName)
                        || line.contains("this.call(" + varName)
                        || line.contains(".call(" + varName)) {
                    varsToWiden.add(varName);
                    break;
                }
            }
        }

        if (varsToWiden.isEmpty()) return content;

        // Apply fixes
        for (int i = 0; i < lines.length; i++) {
            for (String varName : varsToWiden) {
                // Fix declarations with cast: String var = (String)x.get(...) → Object var = x.get(...)
                Matcher m = STRING_CAST_GET_DECL.matcher(lines[i]);
                if (m.matches() && m.group(2).equals(varName)) {
                    lines[i] = m.group(1) + "Object " + varName + " = " + m.group(3) + ";";
                    modified = true;
                    break;
                }
                // Fix declarations without cast: String var = expr; → Object var = expr;
                // (variable declared from a String-returning method but reassigned from .get())
                Integer declLine = stringDeclLines.get(varName);
                if (declLine != null && declLine == i) {
                    Matcher dm = STRING_VAR_DECL.matcher(lines[i]);
                    if (dm.matches() && dm.group(2).equals(varName)) {
                        lines[i] = dm.group(1) + "Object " + varName + " = " + dm.group(3) + ";";
                        modified = true;
                        break;
                    }
                }
                // Fix reassignments: var = (String)x.get(...) → var = x.get(...)
                Matcher m2 = STRING_CAST_GET_ASSIGN.matcher(lines[i]);
                if (m2.matches() && m2.group(2).equals(varName)) {
                    lines[i] = m2.group(1) + varName + " = " + m2.group(3) + ";";
                    modified = true;
                    break;
                }
            }
        }

        return modified ? String.join("\n", lines) : content;
    }

    // ========================================================================
    // Fix: tableName null-guard pattern in fromTable/toTable
    // ========================================================================
    // Vineflower decompiles the parameter-reassignment pattern incorrectly:
    //   if (this.tableName == null || param.rawget(this.tableName) instanceof KahluaTable) {
    //       KahluaTable _param = (KahluaTable)param.rawget(this.tableName);
    //       ... uses _param ...
    //   }
    // The correct logic is:
    //   if (this.tableName != null && param.rawget(this.tableName) instanceof KahluaTable) {
    //       param = (KahluaTable)param.rawget(this.tableName);
    //   }
    //   ... uses param ...

    private static final Pattern FROM_TABLE_BAD_IF = Pattern.compile(
            "^(\\s*)if \\(this\\.tableName == null \\|\\| (\\w+)\\.rawget\\(this\\.tableName\\) instanceof KahluaTable\\) \\{$"
    );
    private static final Pattern FROM_TABLE_BAD_ASSIGN = Pattern.compile(
            "^(\\s*)KahluaTable (\\w+) = \\(KahluaTable\\)(\\w+)\\.rawget\\(this\\.tableName\\);$"
    );

    private static String fixTableNameNullGuardPattern(String content) {
        if (!content.contains("this.tableName == null || ")) return content;

        String[] lines = content.split("\n", -1);
        boolean modified = false;
        int i = 0;

        while (i < lines.length) {
            Matcher ifM = FROM_TABLE_BAD_IF.matcher(lines[i]);
            if (ifM.matches() && i + 1 < lines.length) {
                String indent = ifM.group(1);
                String paramName = ifM.group(2);
                Matcher assignM = FROM_TABLE_BAD_ASSIGN.matcher(lines[i + 1]);
                if (assignM.matches() && assignM.group(3).equals(paramName)) {
                    String localName = assignM.group(2);
                    // Rewrite the if condition
                    lines[i] = indent + "if (this.tableName != null && "
                            + paramName + ".rawget(this.tableName) instanceof KahluaTable) {";
                    // Reassign the parameter instead of creating a new local
                    lines[i + 1] = assignM.group(1) + paramName + " = (KahluaTable)"
                            + paramName + ".rawget(this.tableName);";
                    modified = true;
                    // Find the closing brace of this if block and move
                    // remaining statements out of it
                    int depth = 1;
                    int j = i + 2;
                    // Replace uses of the local with the parameter name
                    while (j < lines.length && depth > 0) {
                        depth += countChar(lines[j], '{') - countChar(lines[j], '}');
                        if (depth > 0) {
                            lines[j] = lines[j].replace(localName + ".", paramName + ".");
                            lines[j] = lines[j].replace(localName + " ", paramName + " ");
                        }
                        j++;
                    }
                    // j is now at the line after the closing brace.
                    // The closing brace line is j-1. We need to move
                    // statements between i+2 and j-2 outside the if block,
                    // and remove the closing brace.
                    // Actually simpler: just remove the if/close braces and
                    // dedent the body — but that changes structure. Instead,
                    // the rewrite already fixes the null guard. The only
                    // remaining issue is that the body should be outside
                    // the if block. Let's keep it inside — when tableName
                    // is null, we skip the if block entirely and don't
                    // execute the rawget. But the original code needs the
                    // rawget to happen regardless...
                    //
                    // Actually, looking more carefully: when tableName is null,
                    // the original bytecode does NOT enter the sub-table lookup.
                    // It just uses the table directly. So the fix is correct:
                    // - tableName != null && instanceof → enter block, reassign param
                    // - otherwise → skip block, use param as-is
                    // But the statements AFTER the cast (like rawget(getShortName()))
                    // need to be OUTSIDE the if block. Let's restructure.

                    // Find the closing brace
                    depth = 1;
                    int closeIdx = -1;
                    for (int k = i + 2; k < lines.length; k++) {
                        depth += countChar(lines[k], '{') - countChar(lines[k], '}');
                        if (depth == 0) {
                            closeIdx = k;
                            break;
                        }
                    }

                    if (closeIdx > 0) {
                        // Move lines between i+2..closeIdx-1 outside the if block
                        // by removing the if open/close braces and keeping just
                        // the condition + reassignment
                        List<String> newLines = new ArrayList<>();
                        // Everything before the if
                        for (int k = 0; k < i; k++) newLines.add(lines[k]);
                        // The if + reassignment + close brace
                        newLines.add(lines[i]);
                        newLines.add(lines[i + 1]);
                        newLines.add(indent + "}");
                        // The body lines that were inside, now outside (dedented)
                        String innerIndent = indent + "    ";
                        for (int k = i + 2; k < closeIdx; k++) {
                            String line = lines[k];
                            // Replace local name with param name
                            line = line.replace(localName + ".", paramName + ".");
                            line = line.replace(localName + " ", paramName + " ");
                            // Dedent by one level if inside the if block
                            if (line.startsWith(innerIndent)) {
                                line = indent + line.substring(innerIndent.length());
                            }
                            newLines.add(line);
                        }
                        // Skip the old closing brace (closeIdx)
                        // Everything after
                        for (int k = closeIdx + 1; k < lines.length; k++) {
                            newLines.add(lines[k]);
                        }
                        lines = newLines.toArray(new String[0]);
                        // Don't advance i — re-check in case of adjacent patterns
                        continue;
                    }
                }
            }
            i++;
        }

        return modified ? String.join("\n", lines) : content;
    }

    // ========================================================================
    // Fix: SandboxOptions fromTable/toTable bytecode-matching rewrites
    // ========================================================================
    // Vineflower decompiles fromTable with a double rawget call and no early return:
    //   if (this.tableName != null && tablex.rawget(this.tableName) instanceof KahluaTable) {
    //       tablex = (KahluaTable)tablex.rawget(this.tableName);
    //   }
    // The original bytecode stores rawget to a local, checks instanceof, and returns
    // early if it's not a KahluaTable. The double rawget also produces extra invoke bytecodes.
    //
    // toTable is even worse — the guard is inverted (!(... instanceof KahluaTable))
    // then immediately casts to KahluaTable, which would ClassCastException. The
    // original creates a new sub-table when one doesn't exist, or reuses the existing one.

    private static String fixSandboxFromToTable(String content) {
        if (!content.contains("class SandboxOptions")) return content;

        // fromTable: 5 identical occurrences across inner classes
        String oldFromTable =
                "        public void fromTable(KahluaTable tablex) {\n" +
                "            if (this.tableName != null && tablex.rawget(this.tableName) instanceof KahluaTable) {\n" +
                "                tablex = (KahluaTable)tablex.rawget(this.tableName);\n" +
                "            }\n" +
                "            var object = tablex.rawget(this.getShortName());\n" +
                "            if (object != null) {\n" +
                "                this.setValueFromObject(object);\n" +
                "            }\n" +
                "        }";
        String newFromTable =
                "        public void fromTable(KahluaTable tablex) {\n" +
                "            if (this.tableName != null) {\n" +
                "                Object var2 = tablex.rawget(this.tableName);\n" +
                "                if (var2 instanceof KahluaTable) {\n" +
                "                    tablex = (KahluaTable)var2;\n" +
                "                } else {\n" +
                "                    return;\n" +
                "                }\n" +
                "            }\n" +
                "            Object object = tablex.rawget(this.getShortName());\n" +
                "            if (object != null) {\n" +
                "                this.setValueFromObject(object);\n" +
                "            }\n" +
                "        }";
        content = content.replace(oldFromTable, newFromTable);

        // toTable: 5 identical occurrences across inner classes
        String oldToTable =
                "        public void toTable(KahluaTable table0x) {\n" +
                "            if (this.tableName != null && !(table0x.rawget(this.tableName) instanceof KahluaTable)) {\n" +
                "                KahluaTable _table0x = (KahluaTable)table0x.rawget(this.tableName);\n" +
                "                KahluaTable table1 = LuaManager.platform.newTable();\n" +
                "                _table0x.rawset(this.tableName, table1);\n" +
                "                _table0x = table1;\n" +
                "            }\n" +
                "\n" +
                "            table0x.rawset(this.getShortName(), this.getValueAsObject());\n" +
                "        }";
        String newToTable =
                "        public void toTable(KahluaTable table0x) {\n" +
                "            if (this.tableName != null) {\n" +
                "                Object var2 = table0x.rawget(this.tableName);\n" +
                "                if (var2 instanceof KahluaTable) {\n" +
                "                    table0x = (KahluaTable)var2;\n" +
                "                } else {\n" +
                "                    KahluaTable table1 = LuaManager.platform.newTable();\n" +
                "                    table0x.rawset(this.tableName, table1);\n" +
                "                    table0x = table1;\n" +
                "                }\n" +
                "            }\n" +
                "            table0x.rawset(this.getShortName(), this.getValueAsObject());\n" +
                "        }";
        content = content.replace(oldToTable, newToTable);

        return content;
    }

    // ========================================================================
    // Binary compatibility: annotate public methods with collection return types
    // ========================================================================

    private static final Pattern PUBLIC_COLLECTION_GETTER = Pattern.compile(
        "^(\\s*)public\\s+(ArrayList|HashMap|HashSet|LinkedList|ConcurrentHashMap|CopyOnWriteArrayList)" +
        "(\\s*<[^>]*>)?\\s+(\\w+)\\s*\\("
    );

    // ========================================================================
    // Fix: ShortBuffer.put() missing (short) cast
    // Vineflower decompiles short variables as int, then emits
    // shortBuffer.put(intVar) which won't compile without a cast.
    // ========================================================================

    private static final Pattern SHORT_BUFFER_PUT_NO_CAST = Pattern.compile(
            "(\\bshortBuffer\\w*\\.put\\()([a-zA-Z_]\\w*)(\\);)"
    );

    private static String fixShortBufferPutMissingCast(String content) {
        return SHORT_BUFFER_PUT_NO_CAST.matcher(content)
                .replaceAll("$1(short)$2$3");
    }

    /**
     * Adds a warning comment above public methods that return concrete collection types.
     * Changing these return types (e.g., ArrayList → CopyOnWriteArrayList) breaks binary
     * compatibility with vanilla classes that call these methods.
     */
    private static String addBinaryCompatWarnings(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = PUBLIC_COLLECTION_GETTER.matcher(lines[i]);
            if (m.find()) {
                String indent = m.group(1);
                // Don't add if already has a warning comment above
                if (i > 0 && lines[i - 1].contains("BINARY COMPAT")) continue;
                lines[i] = indent + "// BINARY COMPAT: return type must match vanilla bytecode — do not change\n" + lines[i];
                modified = true;
            }
        }

        return modified ? String.join("\n", lines) : content;
    }

    // ========================================================================
    // Fix 33: Missing LuaManager$GlobalObject$2 (dead-code anonymous Comparator)
    // ========================================================================

    private static String fixMissingLuaManagerComparator(String content) {
        if (!content.contains("class GlobalObject")) return content;

        // The decompiler collapses getSaveDirectoryTable() to a stub, omitting the
        // anonymous Comparator<File> that exists as $2 in the original bytecode.
        // This shifts the FileVisitor from $3 to $2. Fix: insert an unused Comparator
        // allocation to preserve $2 numbering. Can't use if(false) — javac eliminates it.
        // Move the Comparator to a separate holder method so getSaveDirectoryTable
        // stays clean (3 instructions matching the original). The anonymous class $2
        // numbering is preserved because the holder appears before the FileVisitor ($3)
        // in source order. The extra method is tolerated in semantic mode.
        String oldMethod =
                "        @LuaMethod(name = \"getSaveDirectoryTable\", global = true)\n" +
                "        public static KahluaTable getSaveDirectoryTable() {\n" +
                "            return LuaManager.platform.newTable();\n" +
                "        }";
        String newMethod =
                "        @SuppressWarnings(\"unused\")\n" +
                "        private static java.util.Comparator<java.io.File> comparatorHolder() {\n" +
                "            return new java.util.Comparator<java.io.File>() {\n" +
                "                public int compare(java.io.File file0, java.io.File file1) {\n" +
                "                    return Long.valueOf(file1.lastModified()).compareTo(file0.lastModified());\n" +
                "                }\n" +
                "            };\n" +
                "        }\n" +
                "\n" +
                "        @LuaMethod(name = \"getSaveDirectoryTable\", global = true)\n" +
                "        public static KahluaTable getSaveDirectoryTable() {\n" +
                "            return LuaManager.platform.newTable();\n" +
                "        }";

        return content.replace(oldMethod, newMethod);
    }

    // ========================================================================
    // Fix 34: Missing CharacterSoundEmitter$1 (synthetic switch-map class)
    // ========================================================================

    private static String fixMissingCharacterSoundEmitterSwitchMap(String content) {
        if (!content.contains("class CharacterSoundEmitter")) return content;

        // The original bytecode has a synthetic $1 switch-map class for the footstep
        // enum, but the decompiler correctly used if-else chains. Add a private method
        // with a switch statement to force javac to generate the synthetic $1 class.
        // Can't use if(false) — javac eliminates it.
        String oldSig = "    CharacterSoundEmitter.footstep getFootstepToPlay() {";
        String newSig =
                "    @SuppressWarnings(\"unused\")\n" +
                "    private static int switchMapHolder(CharacterSoundEmitter.footstep f) {\n" +
                "        switch (f) {\n" +
                "            case upstairs: return 1;\n" +
                "            case grass: return 2;\n" +
                "            case wood: return 3;\n" +
                "            case concrete: return 4;\n" +
                "            case gravel: return 5;\n" +
                "            case snow: return 6;\n" +
                "            default: return 0;\n" +
                "        }\n" +
                "    }\n" +
                "\n" +
                "    CharacterSoundEmitter.footstep getFootstepToPlay() {";

        return content.replace(oldSig, newSig);
    }

    // ========================================================================
    // Bytecode-matching: ItemContainer try-finally return pattern
    // ========================================================================
    // The decompiler produces:
    //   InventoryItem var;
    //   try { var = this.getBest(...); } finally { ... }
    //   return var;
    // The original bytecode has:
    //   try { return this.getBest(...); } finally { ... }
    // This affects 10 methods in ItemContainer.

    private static final String[] ITEM_CONTAINER_TRY_FINALLY_METHODS = {
            "getBestType",
            "getBestTypeRecurse",
            "getBestEval",
            "getBestEvalRecurse",
            "getBestEvalArg",
            "getBestEvalArgRecurse",
            "getBestTypeEval",
            "getBestTypeEvalRecurse",
            "getBestTypeEvalArg",
            "getBestTypeEvalArgRecurse",
    };

    private static String fixItemContainerTryFinallyReturn(String content) {
        if (!content.contains("class ItemContainer")) {
            return content;
        }

        for (String methodName : ITEM_CONTAINER_TRY_FINALLY_METHODS) {
            content = fixOneTryFinallyReturn(content, methodName);
        }
        return content;
    }

    private static String fixOneTryFinallyReturn(String content, String methodName) {
        // Find the method
        String methodSig = "public InventoryItem " + methodName + "(";
        int methodStart = content.indexOf(methodSig);
        if (methodStart < 0) {
            return content;
        }

        // Find the method body end by brace counting
        int braceStart = content.indexOf('{', methodStart);
        if (braceStart < 0) {
            return content;
        }
        int depth = 0;
        int methodEnd = -1;
        for (int i = braceStart; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    methodEnd = i + 1;
                    break;
                }
            }
        }
        if (methodEnd < 0) {
            return content;
        }

        String methodBody = content.substring(methodStart, methodEnd);

        // Match the pattern:
        //   InventoryItem <var>;
        //   try {
        //       <var> = this.getBest...(args);
        //   } finally {
        //       ...release lines...
        //   }
        //
        //   return <var>;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "([ \\t]+)InventoryItem (\\w+);\\n" +
                "\\1try \\{\\n" +
                "\\1    \\2 = (this\\.getBest\\w*\\([^)]*\\));\\n" +
                "\\1\\} finally \\{\\n" +
                "((?:\\1    [^\\n]+\\n)+)" +
                "\\1\\}\\n" +
                "\\n" +
                "\\1return \\2;\\n"
        );

        java.util.regex.Matcher matcher = pattern.matcher(methodBody);
        if (!matcher.find()) {
            return content;
        }

        String indent = matcher.group(1);
        String callExpr = matcher.group(3);
        String finallyBody = matcher.group(4);

        String replacement =
                indent + "try {\n" +
                indent + "    return " + callExpr + ";\n" +
                indent + "} finally {\n" +
                finallyBody +
                indent + "}\n";

        String newMethodBody = methodBody.substring(0, matcher.start())
                + replacement
                + methodBody.substring(matcher.end());

        return content.substring(0, methodStart) + newMethodBody + content.substring(methodEnd);
    }

    // ========================================================================
    // Bytecode-matching: ZomboidHashMap entry.key re-read elimination
    // ========================================================================
    // The decompiler generates:
    //   var object1 = entry.key;
    //   if (entry.key == object0 || ...)
    // The original bytecode caches entry.key in a local and uses it for both
    // the == check and the equals() call:
    //   Object object1 = entry.key;
    //   if (object1 == object0 || ...)

    private static String fixZomboidHashMapEntryKeyReread(String content) {
        if (!content.contains("class ZomboidHashMap")) {
            return content;
        }

        // Pattern: "var objectN = entry.key;\n ... if (entry.key == objectM"
        // Replace with: "Object objectN = entry.key;\n ... if (objectN == objectM"
        // Handle both entry variants: entry, entry0, entry1, etc.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(var (\\w+) = (entry\\w*)\\.key;\\n)(\\s*if \\()\\3\\.key( ==)"
        );
        java.util.regex.Matcher matcher = pattern.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(2);
            String replacement = "Object " + varName + " = " + matcher.group(3) + ".key;\n"
                    + matcher.group(4) + varName + matcher.group(5);
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ========================================================================
    // Fix: IsoFire Rand.Next constant folding
    // ========================================================================
    // Vineflower decompiles "-16 + -16 + Rand.Next(32)" which javac constant-folds
    // the first two constants into -32. The original bytecode has separate BIPUSH -16
    // instructions because -16 + Rand.Next(32) is a sub-expression. Fix by adding
    // parentheses to force the correct evaluation order.

    private static String fixIsoFireRandNextFolding(String content) {
        if (!content.contains("class IsoFire")) return content;

        content = content.replace(
                "-16 + -16 + Rand.Next(32)",
                "-16 + (-16 + Rand.Next(32))"
        );
        content = content.replace(
                "-85 + -16 + Rand.Next(32)",
                "-85 + (-16 + Rand.Next(32))"
        );
        return content;
    }

    // ========================================================================
    // Fix: MPStatistic rawset overload — (Object)int cast forces wrong overload
    // ========================================================================
    // KahluaTable has rawset(int, Object) and rawset(Object, Object).
    // Vineflower decompiles as rawset((Object)int2, ...) which autoboxes the int
    // and calls rawset(Object, Object). Original bytecode calls rawset(int, Object).
    // Fix: remove the (Object) cast so the int overload is selected.

    private static String fixMPStatisticRawsetOverload(String content) {
        if (!content.contains("class MPStatistic")) return content;
        content = content.replace(
                "table4.rawset((Object)int2, (double)udpConnection.statistic.FPSHistogramm[int2])",
                "table4.rawset(int2, (Object)(double)udpConnection.statistic.FPSHistogramm[int2])"
        );
        return content;
    }

    // ========================================================================
    // Fix: ClimbOverFenceState / ClimbThroughWindowState float++ → += 1.1F
    // ========================================================================
    // Vineflower decompiles `fload v; ldc 1.1f; fadd; fstore v` as `float++`
    // which is `float += 1.0f`. The original constant is 1.1f, not 1.0f.
    // The -= 0.1F cases are correct; only the S and E switch cases are wrong.

    private static String fixClimbStateFloatIncrement(String content) {
        if (!content.contains("ClimbOverFenceState") && !content.contains("ClimbThroughWindowState")) {
            return content;
        }
        // ClimbOverFenceState: float2++ and float1++ in switch(directions)
        content = content.replace(
                "case S:\n                    float2++;\n                    break;",
                "case S:\n                    float2 += 1.1F;\n                    break;"
        );
        content = content.replace(
                "case E:\n                    float1++;",
                "case E:\n                    float1 += 1.1F;"
        );
        // ClimbThroughWindowState: float5++ and float4++ in switch(directions)
        content = content.replace(
                "case S:\n                        float5++;\n                        break;",
                "case S:\n                        float5 += 1.1F;\n                        break;"
        );
        content = content.replace(
                "case E:\n                        float4++;",
                "case E:\n                        float4 += 1.1F;"
        );
        return content;
    }

    // ========================================================================
    // Fix: UIServerToolbox DialogButton float→int cast
    // ========================================================================
    // Vineflower decompiles integer constants as (float)30, (float)225 etc., but
    // the original bytecode uses BIPUSH 30, SIPUSH 225 (int push). The float
    // overload of DialogButton is called instead of the int overload.
    // Fix: cast `this` to UIEventHandler and remove float casts.

    private static String fixUIServerToolboxFloatCast(String content) {
        if (!content.contains("class UIServerToolbox")) return content;

        content = content.replace(
                "new DialogButton(this, (float)30, (float)225",
                "new DialogButton((UIEventHandler)this, 30, 225"
        );
        content = content.replace(
                "new DialogButton(this, (float)80, (float)225",
                "new DialogButton((UIEventHandler)this, 80, 225"
        );
        return content;
    }

    // ========================================================================
    // Fix 35: Missing AnimState lambda$getAnimNodes$0 (dead lambda body)
    // ========================================================================
    // The original bytecode has a synthetic lambda$getAnimNodes$0 method that
    // formats AnimNode info using String.format("%s: %s", m_Name, getConditionsString()).
    // javac kept the lambda method body but eliminated the invokedynamic call site
    // because it was guarded by `static final boolean = false`. Vineflower
    // correctly omits both the call site and the lambda body. Add the method
    // explicitly to match the original class structure.

    private static String fixAnimStateMissingLambda(String content) {
        if (!content.contains("class AnimState")) return content;

        // Inject the dead lambda method before the closing brace of the class.
        // The method has no call site — it exists as dead code in the original.
        String marker = "    protected void clear() {\n" +
                "        this.m_Nodes.clear();\n" +
                "        this.m_Set = null;\n" +
                "    }\n" +
                "}";
        String replacement = "    protected void clear() {\n" +
                "        this.m_Nodes.clear();\n" +
                "        this.m_Set = null;\n" +
                "    }\n" +
                "\n" +
                "    @SuppressWarnings(\"unused\")\n" +
                "    private static String lambda$getAnimNodes$0(AnimNode animNode) {\n" +
                "        return String.format(\"%s: %s\", animNode.m_Name, animNode.getConditionsString());\n" +
                "    }\n" +
                "}";

        return content.replace(marker, replacement);
    }

    /**
     * Fix Vineflower bug where yield appears before an if-block in switch expressions,
     * making the if-block unreachable. Moves the yield to after the if-block as the
     * default fallback.
     */
    private static String fixYieldBeforeIf(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int i = 0;
        boolean modified = false;

        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("yield ") && trimmed.endsWith(";")
                    && i + 1 < lines.length && lines[i + 1].trim().startsWith("if (")) {
                String yieldLine = lines[i];
                String yieldIndent = yieldLine.substring(0, yieldLine.indexOf("yield"));
                int ifStart = i + 1;
                int depth = 0;
                int ifEnd = ifStart;
                for (int j = ifStart; j < lines.length; j++) {
                    for (char c : lines[j].toCharArray()) {
                        if (c == '{') depth++;
                        else if (c == '}') depth--;
                    }
                    if (depth <= 0) {
                        ifEnd = j;
                        break;
                    }
                }
                for (int j = ifStart; j <= ifEnd; j++) {
                    result.append(lines[j]).append('\n');
                }
                result.append(yieldIndent).append(trimmed).append('\n');
                i = ifEnd + 1;
                modified = true;
            } else {
                result.append(lines[i]).append('\n');
                i++;
            }
        }

        if (modified && result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return modified ? result.toString() : content;
    }

    // ========================================================================
    // Fix: RenderThread lambda ordering in lockStepRenderStep
    // ========================================================================
    // The original bytecode assigns lambda indices as:
    //   $1 = Display.processMessages()
    //   $2 = SpriteRenderer.instance.postRender()
    //   $3 = Display.update(true); checkControllers()
    // The decompiler outputs them in source order (postRender, update, processMessages),
    // which produces $1=postRender, $2=update, $3=processMessages (rotated).
    // Fix: extract lambdas as Runnable variables declared in the original order,
    // so javac assigns the correct indices.

    // ========================================================================
    // Fix: PolygonalMap2.findPath semaphore variable finally duplication
    // ========================================================================
    // Vineflower fails to reconstruct try-finally and falls back to a semaphore
    // variable pattern with 9 copies of the cleanup block. Rewrite with a single
    // try-finally, replacing break-to-label exits with direct returns.

    private static String fixPolygonalMap2FindPath(String content) {
        if (!content.contains("class PolygonalMap2")) return content;
        if (!content.contains("VF: Semaphore variable")) return content;

        String methodSig = "    private boolean findPath(PolygonalMap2.PathFindRequest pathFindRequest, boolean boolean0) {";
        int methodStart = content.indexOf(methodSig);
        if (methodStart < 0) return content;

        // Find method body end by brace counting
        int braceStart = content.indexOf('{', methodStart);
        if (braceStart < 0) return content;
        int depth = 0;
        int methodEnd = -1;
        for (int i = braceStart; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    methodEnd = i + 1;
                    break;
                }
            }
        }
        if (methodEnd < 0) return content;

        String replacement = POLYGONAL_MAP2_FIND_PATH_REWRITE;
        return content.substring(0, methodStart) + replacement + content.substring(methodEnd);
    }

    private static final String POLYGONAL_MAP2_FIND_PATH_REWRITE =
        "    private boolean findPath(PolygonalMap2.PathFindRequest pathFindRequest, boolean boolean0) {\n" +
        "        byte byte0 = 16;\n" +
        "        if (!(pathFindRequest.mover instanceof IsoZombie)) {\n" +
        "            byte0 |= 4;\n" +
        "        }\n" +
        "\n" +
        "        if ((int)pathFindRequest.startZ == (int)pathFindRequest.targetZ\n" +
        "            && !this.lcc\n" +
        "                .isNotClear(\n" +
        "                    this, pathFindRequest.startX, pathFindRequest.startY, pathFindRequest.targetX, pathFindRequest.targetY, (int)pathFindRequest.startZ, byte0\n" +
        "                )) {\n" +
        "            pathFindRequest.path.addNode(pathFindRequest.startX, pathFindRequest.startY, pathFindRequest.startZ);\n" +
        "            pathFindRequest.path.addNode(pathFindRequest.targetX, pathFindRequest.targetY, pathFindRequest.targetZ);\n" +
        "            if (boolean0) {\n" +
        "                for (PolygonalMap2.VisibilityGraph visibilityGraph0 : this.graphs) {\n" +
        "                    visibilityGraph0.render();\n" +
        "                }\n" +
        "            }\n" +
        "\n" +
        "            return true;\n" +
        "        } else {\n" +
        "            this.astar.init(this.graphs, this.squareToNode);\n" +
        "            this.astar.knownBlockedEdges.clear();\n" +
        "\n" +
        "            for (int int0 = 0; int0 < pathFindRequest.knownBlockedEdges.size(); int0++) {\n" +
        "                KnownBlockedEdges knownBlockedEdges = pathFindRequest.knownBlockedEdges.get(int0);\n" +
        "                PolygonalMap2.Square square0 = this.getSquare(knownBlockedEdges.x, knownBlockedEdges.y, knownBlockedEdges.z);\n" +
        "                if (square0 != null) {\n" +
        "                    this.astar.knownBlockedEdges.put(square0.ID, knownBlockedEdges);\n" +
        "                }\n" +
        "            }\n" +
        "\n" +
        "            PolygonalMap2.VisibilityGraph visibilityGraph1 = null;\n" +
        "            PolygonalMap2.VisibilityGraph visibilityGraph2 = null;\n" +
        "            PolygonalMap2.SearchNode searchNode0 = null;\n" +
        "            PolygonalMap2.SearchNode searchNode1 = null;\n" +
        "            boolean boolean1 = false;\n" +
        "            boolean boolean2 = false;\n" +
        "\n" +
        "            try {\n" +
        "                int int1;\n" +
        "                PolygonalMap2.Square square1 = this.getSquare(\n" +
        "                    (int)pathFindRequest.startX, (int)pathFindRequest.startY, (int)pathFindRequest.startZ\n" +
        "                );\n" +
        "                if (square1 != null && !square1.isReallySolid()) {\n" +
        "                    if (square1.has(504)) {\n" +
        "                        searchNode0 = this.astar.getSearchNode(square1);\n" +
        "                    } else {\n" +
        "                        PolygonalMap2.VisibilityGraph visibilityGraph3 = this.astar.getVisGraphForSquare(square1);\n" +
        "                        if (visibilityGraph3 != null) {\n" +
        "                            if (!visibilityGraph3.created) {\n" +
        "                                visibilityGraph3.create();\n" +
        "                            }\n" +
        "\n" +
        "                            PolygonalMap2.Node node0 = null;\n" +
        "                            int1 = visibilityGraph3.getPointOutsideObstacles(\n" +
        "                                pathFindRequest.startX, pathFindRequest.startY, pathFindRequest.startZ, this.adjustStartData\n" +
        "                            );\n" +
        "                            if (int1 == -1) {\n" +
        "                                return false;\n" +
        "                            }\n" +
        "\n" +
        "                            if (int1 == 1) {\n" +
        "                                boolean1 = true;\n" +
        "                                node0 = this.adjustStartData.node;\n" +
        "                                if (this.adjustStartData.isNodeNew) {\n" +
        "                                    visibilityGraph1 = visibilityGraph3;\n" +
        "                                }\n" +
        "                            }\n" +
        "\n" +
        "                            if (node0 == null) {\n" +
        "                                node0 = PolygonalMap2.Node.alloc()\n" +
        "                                    .init(pathFindRequest.startX, pathFindRequest.startY, (int)pathFindRequest.startZ);\n" +
        "                                visibilityGraph3.addNode(node0);\n" +
        "                                visibilityGraph1 = visibilityGraph3;\n" +
        "                            }\n" +
        "\n" +
        "                            searchNode0 = this.astar.getSearchNode(node0);\n" +
        "                        }\n" +
        "                    }\n" +
        "\n" +
        "                    if (searchNode0 == null) {\n" +
        "                        searchNode0 = this.astar.getSearchNode(square1);\n" +
        "                    }\n" +
        "\n" +
        "                    if (!(pathFindRequest.targetX < 0.0F)\n" +
        "                        && !(pathFindRequest.targetY < 0.0F)\n" +
        "                        && this.getChunkFromSquarePos((int)pathFindRequest.targetX, (int)pathFindRequest.targetY) != null) {\n" +
        "                        square1 = this.getSquare(\n" +
        "                            (int)pathFindRequest.targetX, (int)pathFindRequest.targetY, (int)pathFindRequest.targetZ\n" +
        "                        );\n" +
        "                        if (square1 == null || square1.isReallySolid()) {\n" +
        "                            return false;\n" +
        "                        }\n" +
        "\n" +
        "                        if ((\n" +
        "                                (int)pathFindRequest.startX != (int)pathFindRequest.targetX\n" +
        "                                    || (int)pathFindRequest.startY != (int)pathFindRequest.targetY\n" +
        "                                    || (int)pathFindRequest.startZ != (int)pathFindRequest.targetZ\n" +
        "                            )\n" +
        "                            && this.isBlockedInAllDirections(\n" +
        "                                (int)pathFindRequest.targetX, (int)pathFindRequest.targetY, (int)pathFindRequest.targetZ\n" +
        "                            )) {\n" +
        "                            return false;\n" +
        "                        }\n" +
        "\n" +
        "                        if (square1.has(504)) {\n" +
        "                            searchNode1 = this.astar.getSearchNode(square1);\n" +
        "                        } else {\n" +
        "                            PolygonalMap2.VisibilityGraph visibilityGraph4 = this.astar.getVisGraphForSquare(square1);\n" +
        "                            if (visibilityGraph4 != null) {\n" +
        "                                if (!visibilityGraph4.created) {\n" +
        "                                    visibilityGraph4.create();\n" +
        "                                }\n" +
        "\n" +
        "                                PolygonalMap2.Node node1 = null;\n" +
        "                                int1 = visibilityGraph4.getPointOutsideObstacles(\n" +
        "                                    pathFindRequest.targetX, pathFindRequest.targetY, pathFindRequest.targetZ, this.adjustGoalData\n" +
        "                                );\n" +
        "                                if (int1 == -1) {\n" +
        "                                    return false;\n" +
        "                                }\n" +
        "\n" +
        "                                if (int1 == 1) {\n" +
        "                                    boolean2 = true;\n" +
        "                                    node1 = this.adjustGoalData.node;\n" +
        "                                    if (this.adjustGoalData.isNodeNew) {\n" +
        "                                        visibilityGraph2 = visibilityGraph4;\n" +
        "                                    }\n" +
        "                                }\n" +
        "\n" +
        "                                if (node1 == null) {\n" +
        "                                    node1 = PolygonalMap2.Node.alloc()\n" +
        "                                        .init(pathFindRequest.targetX, pathFindRequest.targetY, (int)pathFindRequest.targetZ);\n" +
        "                                    visibilityGraph4.addNode(node1);\n" +
        "                                    visibilityGraph2 = visibilityGraph4;\n" +
        "                                }\n" +
        "\n" +
        "                                searchNode1 = this.astar.getSearchNode(node1);\n" +
        "                            } else {\n" +
        "                                for (int int2 = 0; int2 < this.graphs.size(); int2++) {\n" +
        "                                    PolygonalMap2.VisibilityGraph visibilityGraph5 = this.graphs.get(int2);\n" +
        "                                    if (visibilityGraph5.contains(square1, 1)) {\n" +
        "                                        PolygonalMap2.Node node2 = this.getPointOutsideObjects(\n" +
        "                                            square1, pathFindRequest.targetX, pathFindRequest.targetY\n" +
        "                                        );\n" +
        "                                        visibilityGraph5.addNode(node2);\n" +
        "                                        if (node2.x != pathFindRequest.targetX || node2.y != pathFindRequest.targetY) {\n" +
        "                                            boolean2 = true;\n" +
        "                                            this.adjustGoalData.isNodeNew = false;\n" +
        "                                        }\n" +
        "\n" +
        "                                        visibilityGraph2 = visibilityGraph5;\n" +
        "                                        searchNode1 = this.astar.getSearchNode(node2);\n" +
        "                                        break;\n" +
        "                                    }\n" +
        "                                }\n" +
        "                            }\n" +
        "                        }\n" +
        "\n" +
        "                        if (searchNode1 == null) {\n" +
        "                            searchNode1 = this.astar.getSearchNode(square1);\n" +
        "                        }\n" +
        "                    } else {\n" +
        "                        searchNode1 = this.astar.getSearchNode((int)pathFindRequest.targetX, (int)pathFindRequest.targetY);\n" +
        "                    }\n" +
        "\n" +
        "                    ArrayList arrayList = this.astar.shortestPath(pathFindRequest, searchNode0, searchNode1);\n" +
        "                    if (arrayList != null) {\n" +
        "                        if (arrayList.size() == 1) {\n" +
        "                            pathFindRequest.path.addNode(searchNode0);\n" +
        "                            if (!boolean2\n" +
        "                                && searchNode1.square != null\n" +
        "                                && searchNode1.square.x + 0.5F != pathFindRequest.targetX\n" +
        "                                && searchNode1.square.y + 0.5F != pathFindRequest.targetY) {\n" +
        "                                pathFindRequest.path\n" +
        "                                    .addNode(pathFindRequest.targetX, pathFindRequest.targetY, pathFindRequest.targetZ, 0);\n" +
        "                            } else {\n" +
        "                                pathFindRequest.path.addNode(searchNode1);\n" +
        "                            }\n" +
        "\n" +
        "                            return true;\n" +
        "                        }\n" +
        "\n" +
        "                        this.cleanPath(arrayList, pathFindRequest, boolean1, boolean2, searchNode1);\n" +
        "                        if (pathFindRequest.mover instanceof IsoPlayer && !((IsoPlayer)pathFindRequest.mover).isNPC()) {\n" +
        "                            this.smoothPath(pathFindRequest.path);\n" +
        "                        }\n" +
        "\n" +
        "                        return true;\n" +
        "                    }\n" +
        "\n" +
        "                    return false;\n" +
        "                }\n" +
        "\n" +
        "                return false;\n" +
        "            } finally {\n" +
        "                if (boolean0) {\n" +
        "                    for (PolygonalMap2.VisibilityGraph visibilityGraph6 : this.graphs) {\n" +
        "                        visibilityGraph6.render();\n" +
        "                    }\n" +
        "                }\n" +
        "\n" +
        "                if (visibilityGraph1 != null) {\n" +
        "                    visibilityGraph1.removeNode(searchNode0.vgNode);\n" +
        "                }\n" +
        "\n" +
        "                if (visibilityGraph2 != null) {\n" +
        "                    visibilityGraph2.removeNode(searchNode1.vgNode);\n" +
        "                }\n" +
        "\n" +
        "                for (int int3 = 0; int3 < this.astar.searchNodes.size(); int3++) {\n" +
        "                    this.astar.searchNodes.get(int3).release();\n" +
        "                }\n" +
        "\n" +
        "                if (boolean1 && this.adjustStartData.isNodeNew) {\n" +
        "                    for (int int3 = 0; int3 < this.adjustStartData.node.edges.size(); int3++) {\n" +
        "                        PolygonalMap2.Edge edge0 = this.adjustStartData.node.edges.get(int3);\n" +
        "                        edge0.obstacle.unsplit(this.adjustStartData.node, edge0.edgeRing);\n" +
        "                    }\n" +
        "\n" +
        "                    this.adjustStartData.graph.edges.remove(this.adjustStartData.newEdge);\n" +
        "                }\n" +
        "\n" +
        "                if (boolean2 && this.adjustGoalData.isNodeNew) {\n" +
        "                    for (int int3 = 0; int3 < this.adjustGoalData.node.edges.size(); int3++) {\n" +
        "                        PolygonalMap2.Edge edge0 = this.adjustGoalData.node.edges.get(int3);\n" +
        "                        edge0.obstacle.unsplit(this.adjustGoalData.node, edge0.edgeRing);\n" +
        "                    }\n" +
        "\n" +
        "                    this.adjustGoalData.graph.edges.remove(this.adjustGoalData.newEdge);\n" +
        "                }\n" +
        "            }\n" +
        "        }\n" +
        "    }";

    private static String fixRenderThreadLambdaOrder(String content) {
        if (!content.contains("class RenderThread")) return content;

        String oldMethod =
                "    private static boolean lockStepRenderStep() {\n" +
                "        SpriteRenderState spriteRenderState = SpriteRenderer.instance.acquireStateForRendering(zombie.core.opengl.RenderThread::waitForRenderStateCallback);\n" +
                "        if (spriteRenderState != null) {\n" +
                "            m_cursorVisible = spriteRenderState.bCursorVisible;\n" +
                "            s_performance.spriteRendererPostRender.invokeAndMeasure(() -> SpriteRenderer.instance.postRender());\n" +
                "            s_performance.displayUpdate.invokeAndMeasure(() -> {\n" +
                "                Display.update(true);\n" +
                "                checkControllers();\n" +
                "            });\n" +
                "            if (Core.bDebug && FPSGraph.instance != null) {\n" +
                "                FPSGraph.instance.addRender(System.currentTimeMillis());\n" +
                "            }\n" +
                "\n" +
                "            MPStatisticClient.getInstance().fpsProcess();\n" +
                "            return true;\n" +
                "        } else {\n" +
                "            notifyRenderStateQueue();\n" +
                "            if (!m_waitForRenderState || LuaManager.thread != null && LuaManager.thread.bStep) {\n" +
                "                s_performance.displayUpdate.invokeAndMeasure(() -> Display.processMessages());\n" +
                "            }\n" +
                "\n" +
                "            return true;\n" +
                "        }\n" +
                "    }";
        String newMethod =
                "    private static boolean lockStepRenderStep() {\n" +
                "        // Lambda variables declared in original bytecode order ($1, $2, $3)\n" +
                "        Runnable _lambda1 = () -> Display.processMessages();\n" +
                "        Runnable _lambda2 = () -> SpriteRenderer.instance.postRender();\n" +
                "        Runnable _lambda3 = () -> {\n" +
                "            Display.update(true);\n" +
                "            checkControllers();\n" +
                "        };\n" +
                "        SpriteRenderState spriteRenderState = SpriteRenderer.instance.acquireStateForRendering(zombie.core.opengl.RenderThread::waitForRenderStateCallback);\n" +
                "        if (spriteRenderState != null) {\n" +
                "            m_cursorVisible = spriteRenderState.bCursorVisible;\n" +
                "            s_performance.spriteRendererPostRender.invokeAndMeasure(_lambda2);\n" +
                "            s_performance.displayUpdate.invokeAndMeasure(_lambda3);\n" +
                "            if (Core.bDebug && FPSGraph.instance != null) {\n" +
                "                FPSGraph.instance.addRender(System.currentTimeMillis());\n" +
                "            }\n" +
                "\n" +
                "            MPStatisticClient.getInstance().fpsProcess();\n" +
                "            return true;\n" +
                "        } else {\n" +
                "            notifyRenderStateQueue();\n" +
                "            if (!m_waitForRenderState || LuaManager.thread != null && LuaManager.thread.bStep) {\n" +
                "                s_performance.displayUpdate.invokeAndMeasure(_lambda1);\n" +
                "            }\n" +
                "\n" +
                "            return true;\n" +
                "        }\n" +
                "    }";

        return content.replace(oldMethod, newMethod);
    }

    // ── ActionContext: remove spurious transitionOut check ────────────────
    // Vineflower incorrectly duplicates the transitionOut check from
    // evaluateSubStateTransitions into evaluateCurrentStateTransitions.
    // The original bytecode for evaluateCurrentStateTransitions does NOT
    // check transitionOut — it only exists in the substate variant.
    // This extra check causes transitions with transitionOut=true to
    // break out of the loop without processing, preventing animation
    // state transitions (e.g. attack/shove) from firing correctly.
    private static String fixActionContextTransitionOutCheck(String content) {
        if (!content.contains("class ActionContext ")) return content;
        // Remove the transitionOut early-break in evaluateCurrentStateTransitions.
        // The pattern: after passes() check, the decompiler inserts:
        //   if (actionTransition.transitionOut) { break; }
        // which should not be there.
        content = content.replace(
                "            if (actionTransition.passes(this, 0)) {\n" +
                "                if (actionTransition.transitionOut) {\n" +
                "                    break;\n" +
                "                }\n" +
                "\n" +
                "                if (StringUtils.isNullOrWhitespace(actionTransition.transitionTo))",
                "            if (actionTransition.passes(this, 0)) {\n" +
                "                if (StringUtils.isNullOrWhitespace(actionTransition.transitionTo))");
        return content;
    }

    // ── RandomizedVehicleStoryBase: fix PI/2 angle constant ──────────────
    // Vineflower decompiles `LDC 1.5707964f; FADD; FSTORE` as `++float0`
    // (pre-increment by 1.0f) instead of `float0 += 1.5707964F` (PI/2).
    // This changes vehicle story spawn angles from ~90° offsets to ~57° offsets.
    private static String fixVehicleStorySpawnerAngle(String content) {
        if (!content.contains("class RandomizedVehicleStoryBase ")) return content;
        content = content.replace(
                "vehicleStorySpawner.spawn(floats[0], floats[1], 0.0F, ++float0, this::spawnElement);",
                "float0 += 1.5707964F;\n            vehicleStorySpawner.spawn(floats[0], floats[1], 0.0F, float0, this::spawnElement);");
        return content;
    }
}
