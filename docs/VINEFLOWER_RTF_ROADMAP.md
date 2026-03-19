# Vineflower RTF Fork — Roadmap to Zero PostDecompileTransforms

> **See also**: [POST_TRANSFORM_ELIMINATION.md](./POST_TRANSFORM_ELIMINATION.md) for the
> detailed per-transform tracking document that supersedes the phase breakdown below.
> This document remains as architectural context for the RTF fork.

## Why We Forked Vineflower

The ZomboidDecompiler project decompiles Project Zomboid's `.class` files, recompiles them, and verifies bytecode fidelity. The decompiled source must:
1. **Compile cleanly** with zero errors
2. **Produce bytecode matching the original** when recompiled with the same JDK (Zulu 17.0.1)
3. **Be editable** — developers must be able to modify the source and recompile

Standard Vineflower 1.11.2 produces source that requires 97 PostDecompileTransforms (string-replace hacks) to compile and match bytecode. These transforms are fragile, hard to maintain, and prevent clean editing of the source.

## What RTF Mode Does

The Vineflower fork adds a `ROUNDTRIP_FIDELITY` option (off by default) that preserves original bytecode patterns in the decompiled output:

| Feature | Standard Vineflower | RTF Mode |
|---------|-------------------|----------|
| Guard clause direction | Canonicalizes (may invert) | Preserves original IFEQ/IFNE direction |
| Operand evaluation order | May reorder commutative operands | Preserves stack evaluation order |
| Compound assignment | Converts `x = x + y` to `x += y` | Preserves original form |
| Switch case ordering | Sorts numerically | Preserves bytecode case order |
| Switch expressions | Upgrades to Java 14+ | Keeps traditional switch statements |
| Type widening casts | Strips implicit widenings | Preserves F2D, I2L, etc. |
| Ternary collapse | Collapses if/else returns | Preserves separate return statements |
| PPI/MMI simplification | Inlines `++i` into expressions | Preserves as separate statements |
| Variable types | Infers narrowest type | Pins OBJECT types to LocalVariableTable |
| Synthetic classes | Normal processing | Disables RTF for `$N` inner classes |

### RTF Changes in Vineflower Source

All changes gated behind `if (DecompilerContext.isRoundtripFidelity())`:

- **IfHelper.java** (lines 367, 574, 700): 3 guards blocking cosmetic condition negation
- **SimplifyExprentsHelper.java**: Skip PPI/MMI/IPP/IMM, skip return-ternary collapse
- **SecondaryFunctionsHelper.java**: Skip compound assignment (except enums), preserve widening casts
- **ExprProcessor.java**: POP+INVOKESTATIC qualifier for instance-qualified static calls
- **InvocationExprent.java**: staticInstanceQualifier field for rendering
- **SwitchStatement.java**: Preserve bytecode case ordering
- **SwitchExpressionHelper.java**: Skip switch expression transformation
- **VarTypeProcessor.java**: Pin OBJECT LVT types
- **StackVarsProcessor.java**: Disable cross-node next, disable simplifyAcrossStack
- **MethodProcessor.java**: Disable RTF for synthetic `$N` inner classes

## Current Status

### Compilation
- **0 errors**, 2979 classes compiled successfully
- 22 decompilation artifacts fixed via manual source edits (unreachable code, missing returns, byte casts)
- These manual fixes need to be automated in Vineflower

### Tier Distribution (32,970 methods)
| Tier | Count | % | Description |
|------|-------|---|-------------|
| EXACT | 30,707 | 93.1% | Normalized instructions identical |
| STRUCTURAL | 1,335 | 4.0% | Same instructions, label/GOTO differences |
| SORTED_MULTISET | 794 | 2.4% | Same instructions, order lost |
| FUZZY_COMPUTATION | 108 | 0.3% | 10-35% instruction differences |
| CORE_OPS_ONLY | 15 | 0.0% | Strips everything except calls/arithmetic |
| NONE | 10 | 0.0% | Unmatched |

## Path to Zero Transforms

### Phase 1: Fix Vineflower Decompilation Artifacts (22 manual fixes → Vineflower)

These are patterns that the current RTF mode produces but are invalid Java:

1. **Unreachable code after continue/return** (12 instances): RTF preserves branch direction but leaves dead code after unconditional control flow transfers. Fix: re-enable and fix the `DeadCodeEliminator.java` pass (already written, needs switch-case guard).

2. **Missing returns in string-switch** (6 instances): RTF's switch changes interact with string switch hash-based dispatch, leaving fall-through paths without returns. Fix: add return insertion pass after switch simplification.

3. **Lossy byte/int conversion** (3 instances): RTF preserves wider types from LVT but loses narrowing casts. Fix: add cast insertion when narrowing is needed.

4. **Uninitialized variables** (1 instance): RTF block restructuring moves declarations into inner scopes. Fix: variable declaration hoisting pass.

### Phase 2: Fix Vineflower Type Bugs (~40 transforms)

The largest category. These are real Vineflower bugs unrelated to RTF:

**Priority 1 — Raw/Generic Type Issues (12 transforms)**
- fixRawCollectionTypes, fixRawLambdaAndMethodRef, fixRawSortComparators, fixRawToArrayCast, fixRawForEachCast, fixRawMethodReturnCast, fixRawStreamPath, fixRawLambdaTypeInference, fixRawgetAsBoolean, fixRawsetAmbiguity, fixGenericClassInternals, fixWrongStringCastOnGet
- **Vineflower component**: `GenericMethodInvocationProcessor`, `VarTypeProcessor`, type inference pipeline
- **Root cause**: Vineflower erases generic types in some contexts where they're needed for compilation

**Priority 2 — Type Confusion (7 transforms)**
- fixIntBooleanConfusion, fixBooleanIntConversion, fixMistypedJavaIoFile, fixMistypedStringVar, fixObjectToVar, fixObjectToStringCast, fixBooleanCanonicalization
- **Vineflower component**: `VarTypeProcessor`, `ExprProcessor`
- **Root cause**: Vineflower infers wrong variable types from bytecode usage patterns

**Priority 3 — instanceof Pattern Scope (2 transforms)**
- fixInstanceofPatternScope, fixDuplicateInstanceofPatternVars
- **Vineflower component**: Pattern matching decompilation (Java 16+)
- **Root cause**: Pattern variables escape their if-block scope

**Priority 4 — Other Bugs (16 transforms)**
- Various: fixMakeConcatWithConstants, fixAssertKeywordToExplicit, fixAssertionsDisabled, fixEmptySwitchExpressionCase, fixSwitchOnObject, fixUncaughtExceptionInTry, fixDirectoryStreamForEach, fixLoggerNullAmbiguity, fixDialogButtonAmbiguity, fixAnnotationTypeCasts, fixStringAssignmentNeedsCast, fixShortBufferPutMissingCast, fixVariableShadowsClassName, fixByteCounterVars, fixExternalShadowedFieldRefs

### Phase 3: Improve RTF Bytecode Fidelity (~30 transforms)

These fix the source to produce bytecode matching the original:

**Already handled by RTF:**
- Guard clause direction (IfHelper guards)
- Switch case ordering (SwitchStatement)
- Switch expression suppression (SwitchExpressionHelper)
- Compound assignment preservation (SecondaryFunctionsHelper)
- Widening cast preservation (SecondaryFunctionsHelper)
- PPI/MMI suppression (SimplifyExprentsHelper)

**Still needed:**
- Evaluation order preservation (10 transforms) — partially implemented
- Synchronized return patterns (4 transforms) — partially implemented
- CHM assertion init ordering (4 transforms) — may need special handling
- Try-finally patterns (2 transforms) — needs FinallyProcessor work
- Constant value corrections (5 transforms) — increment values, float casts

### Phase 4: Permanent Transforms (5 transforms — KEEP)

These inject dead bytecode artifacts that no decompiler should produce:
- fixMissingLuaManagerComparator — orphaned anonymous Comparator class
- fixMissingCharacterSoundEmitterSwitchMap — orphaned switch map
- fixAnimStateMissingLambda — dead lambda method body
- fixRenderThreadLambdaOrder — lambda index rotation
- fixPolygonalMap2FindPath — semaphore variable full rewrite

These will always be needed as PostDecompileTransforms.

## Verification Protocol

For each Vineflower fix:
1. Disable the corresponding PostDecompileTransform (comment out the `content = fixXxx(content)` line)
2. Rebuild Vineflower: `cd vineflower && JAVA_HOME=tools/zulu-jdk-17.0.1 ./gradlew jar --rerun-tasks && cp build/libs/vineflower-1.11.2+local-slim.jar build/libs/vineflower-1.11.2.jar`
3. Rebuild ZomboidDecompiler: `cd ZomboidDecompiler && rm -rf build .gradle && ./gradlew build -x test installDist`
4. Run pipeline: `bash ZomboidDecompiler/scripts/b41.sh all`
5. Verify: 0 compile errors, tier distribution not regressed

## Build Workflow

```bash
# 1. Modify Vineflower source
cd /home/jaidaken/optizomb/vineflower
# edit src/org/jetbrains/java/decompiler/...

# 2. Build Vineflower JAR (MUST use --rerun-tasks to avoid caching)
JAVA_HOME=/home/jaidaken/optizomb/tools/zulu-jdk-17.0.1 ./gradlew jar --rerun-tasks
cp build/libs/vineflower-1.11.2+local-slim.jar build/libs/vineflower-1.11.2.jar

# 3. Rebuild ZomboidDecompiler (MUST clean to pick up new JAR)
cd /home/jaidaken/optizomb/ZomboidDecompiler
rm -rf build .gradle
./gradlew build -x test installDist

# 4. Run full pipeline
cd /home/jaidaken/optizomb
bash ZomboidDecompiler/scripts/b41.sh all

# 5. Check results
# - Compilation: look for "Compiled successfully (2979 classes)"
# - Tiers: look for Match tiers in verify output
```

## Target End State

- **5 permanent PostDecompileTransforms** (dead code injection)
- **0 Vineflower bug transforms** (all fixed in fork)
- **0 bytecode fidelity transforms** (all handled by RTF mode)
- **Clean decompiled source** that compiles and matches original bytecode
- **Editable source** that developers can modify and recompile with confidence
