# PostDecompileTransform Elimination Roadmap

> **Goal**: Reduce 69 B41 PostDecompileTransforms to ~5 permanent ones by fixing
> Vineflower's RTF fork to produce correct, compilable, bytecode-faithful source
> without post-hoc string munging.

## Transform Census

| Category | Count | Disposition |
|----------|-------|-------------|
| **vineflower-fixable** | 37 | Eliminate by fixing Vineflower RTF fork |
| **game-specific-hack** | 22 | Permanent — inject dead bytecode or game-specific rewrites |
| **java-limitation** | 12 | Permanent — work around javac behavior or Java language limits |
| **Total** | **71** | **Target: 34 permanent, 37 eliminated** |

> The `addBinaryCompatWarnings` and `fixSpecificFileErrors` meta-transforms are
> counted once each even though they contain multiple sub-fixes internally.

---

## Category 1: vineflower-fixable (37 transforms — ELIMINATE)

These are real Vineflower decompiler bugs that produce wrong types, missing casts,
broken generics, or incorrect control flow. Every one should be fixed in the
Vineflower RTF fork so the PostDecompileTransform can be deleted.

### 1A. Raw/Generic Type Inference (12 transforms)

Vineflower erases generic type arguments in contexts where javac needs them for
overload resolution or type checking. Root cause is in `GenericMethodInvocationProcessor`,
`VarTypeProcessor`, and the type inference pipeline.

| # | Transform | Bug Summary |
|---|-----------|-------------|
| 1 | `fixRawCollectionTypes` | Raw `ArrayList`/`HashMap` instead of parameterized types |
| 2 | `fixRawLambdaAndMethodRef` | Lambda/method-ref loses generic context, becomes raw |
| 3 | `fixRawSortComparators` | `Collections.sort()` / `List.sort()` loses `Comparator<T>` |
| 4 | `fixRawToArrayCast` | `toArray()` result needs `(T[])` cast with generic collection |
| 5 | `fixRawForEachCast` | For-each over raw collection needs element cast |
| 6 | `fixRawMethodReturnCast` | Method returning generic type emitted as raw `Object` |
| 7 | `fixRawStreamPath` | Stream pipeline loses generic type at `.map()` / `.collect()` |
| 8 | `fixRawLambdaTypeInference` | Lambda parameter type not inferred from functional interface |
| 9 | `fixRawgetAsBoolean` | `getAsBoolean()` on raw `OptionalInt`-like loses type |
| 10 | `fixRawsetAmbiguity` | `rawset(Object, Object)` vs `rawset(int, Object)` overload |
| 11 | `fixGenericClassInternals` | Generic inner class methods lose enclosing type params |
| 12 | `fixWrongStringCastOnGet` | `Map.get()` return cast to wrong type (String vs Object) |

### 1B. Type Confusion (7 transforms)

Vineflower infers the wrong variable type from bytecode usage patterns, particularly
`int` vs `boolean`, `File` vs `String`, and `Object` vs specific types.

| # | Transform | Bug Summary |
|---|-----------|-------------|
| 13 | `fixIntBooleanConfusion` | `int` variable used as `boolean` (or vice versa) |
| 14 | `fixBooleanIntConversion` | Boolean expression decompiled as int arithmetic |
| 15 | `fixMistypedJavaIoFile` | Variable typed as `File` but used as `String` path |
| 16 | `fixMistypedStringVar` | Variable typed as `String` but should be other type |
| 17 | `fixObjectToVar` | `Object` type where specific type is inferrable |
| 18 | `fixObjectToStringCast` | Missing `(String)` cast on `Object` return |
| 19 | `fixBooleanCanonicalization` | `boolean` literal 0/1 not canonicalized to false/true |

### 1C. instanceof Pattern Scope (2 transforms)

Java 16+ pattern variables escape their if-block scope in decompiled output.

| # | Transform | Bug Summary |
|---|-----------|-------------|
| 20 | `fixInstanceofPatternScope` | Pattern variable used after if-block (scope escape) |
| 21 | `fixDuplicateInstanceofPatternVars` | Same pattern variable name in multiple branches |

### 1D. Control Flow / Structure (6 transforms)

Vineflower produces incorrect control flow that changes semantics or fails to compile.

| # | Transform | Bug Summary |
|---|-----------|-------------|
| 22 | `fixEmptySwitchExpressionCase` | Empty case in switch expression (won't compile) |
| 23 | `fixSwitchOnObject` | Switch on `Object` type (javac rejects) |
| 24 | `fixUncaughtExceptionInTry` | Exception type in catch not declared throwable |
| 25 | `fixItemContainerTryFinallyReturn` | Try-finally return pattern decompiled incorrectly |
| 26 | `fixDiskFileSeekSwitchStructure` | Switch expression where imperative switch needed |
| 27 | `fixIsoDeadBodyReanimateSwitch` | Switch expression drops case 1 fall-through |

### 1E. Other Decompiler Bugs (10 transforms)

Miscellaneous Vineflower bugs: missing casts, wrong constants, shadowing, concat.

| # | Transform | Bug Summary |
|---|-----------|-------------|
| 28 | `fixMakeConcatWithConstants` | `makeConcatWithConstants` bootstrap not resolved |
| 29 | `fixVariableShadowsClassName` | Local variable name shadows enclosing class name |
| 30 | `fixDialogButtonAmbiguity` | Overload ambiguity (int vs float parameter) |
| 31 | `fixAnnotationTypeCasts` | Missing cast in annotation processing |
| 32 | `fixDirectoryStreamForEach` | `DirectoryStream` iteration decompiled incorrectly |
| 33 | `fixStringAssignmentNeedsCast` | String assignment missing narrowing cast |
| 34 | `fixExternalShadowedFieldRefs` | Field reference shadowed by local in outer scope |
| 35 | `fixLoggerNullAmbiguity` | Logger method overload ambiguity (null argument) |
| 36 | `fixShortBufferPutMissingCast` | `ShortBuffer.put()` missing short cast |
| 37 | `fixByteCounterVars` | Byte counter variable widened to int |

---

## Category 2: game-specific-hack (22 transforms — PERMANENT)

These transforms inject dead bytecode artifacts, fix game-specific patterns that
no decompiler should be expected to handle, or rewrite method bodies to match
original bytecode that was hand-written with unusual patterns.

### 2A. Dead Code / Orphaned Artifacts (5 transforms)

The original JAR contains classes/methods/lambdas that are unreachable or were
orphaned during PZ's build process. No decompiler can synthesize these.

| # | Transform | What It Injects |
|---|-----------|-----------------|
| 1 | `fixMissingLuaManagerComparator` | Orphaned anonymous `Comparator` class |
| 2 | `fixMissingCharacterSoundEmitterSwitchMap` | Orphaned `$SwitchMap` synthetic class |
| 3 | `fixAnimStateMissingLambda` | Dead lambda method body in AnimState |
| 4 | `fixRenderThreadLambdaOrder` | Lambda index rotation to match original numbering |
| 5 | `fixPolygonalMap2FindPath` | Full method rewrite (semaphore variable pattern) |

### 2B. Evaluation Order Fixes (8 transforms)

The original bytecode evaluates sub-expressions in a specific order that Vineflower
reorders. These are single-method, single-site fixes that are cheaper to keep as
transforms than to make Vineflower preserve arbitrary evaluation order.

| # | Transform | Original Pattern |
|---|-----------|-----------------|
| 6 | `fixLuaManagerRunLuaInternalVarSave` | Save string before `getString()` overwrites it |
| 7 | `fixModelLoaderAnimNameSave` | Save animation name before `readLine()` overwrites |
| 8 | `fixServerGUIUpdateCameraVarSave` | Save player param to local before computation |
| 9 | `fixWorldFlaresApplyFlareInline` | Inline color channel multiplication (no temps) |
| 10 | `fixClimateValuesQualifiedStaticCalls` | Instance-qualified static calls + compound assign |
| 11 | `fixMPStatisticClientFloatArray` | `float[]` instead of `Object` for array clone |
| 12 | `fixBaseVehicleUpdateSoundsCompoundAssign` | Compound `-=` instead of `= ... -` |
| 13 | `fixIsoFireRandNextFolding` | Prevent constant folding of `-16 + -16 + Rand.Next(32)` |

### 2C. Constant / Literal Corrections (4 transforms)

Vineflower's PPI/MMI simplification replaces specific float constants with `++`/`--`
(which is `+= 1.0f`). These are game-specific values (1.1f, PI/2, 1.5f).

| # | Transform | Correct Value |
|---|-----------|--------------|
| 14 | `fixClimbStateFloatIncrement` | `1.1f` not `1.0f` (from `++`) |
| 15 | `fixVehicleStorySpawnerAngle` | `1.5707964f` (PI/2) not `1.0f` |
| 16 | `fixAddBloodToMapSubtract` | `1.5f` not `1.0f` (from `--`) |
| 17 | `fixIsoChunkAddCorpsesSubtract` | `1.5f` not `1.0f` (from `--`) |

### 2D. Game-Specific Method Rewrites (5 transforms)

Single-method rewrites that fix game-specific decompilation failures.

| # | Transform | What It Fixes |
|---|-----------|--------------|
| 18 | `fixActionContextTransitionOutCheck` | Remove spurious `transitionOut` check |
| 19 | `fixMPStatisticRawsetOverload` | Remove `(Object)` cast to select int overload |
| 20 | `fixUIServerToolboxFloatCast` | Cast `this` to `UIEventHandler`, remove float casts |
| 21 | `fixSpecificFileErrors` | Multi-fix: ClothingWetness scope, IsoGridSquare dead instanceof, HairOutfitDefinitions raw cast, KahluaThread string cast |
| 22 | `fixZomboidHashMapEntryKeyReread` | Cache `entry.key` in local for `==` and `.equals()` |

---

## Category 3: java-limitation (12 transforms — PERMANENT)

These work around javac behavior, Java language limitations, or bytecode patterns
that cannot be expressed differently in source without changing semantics.

### 3A. Assertion / Clinit Ordering (3 transforms)

Javac synthesizes `$assertionsDisabled` in `<clinit>`. When PZ's original bytecode
has specific `<clinit>` ordering, we must match it exactly.

| # | Transform | What It Does |
|---|-----------|-------------|
| 1 | `fixAssertionsDisabled` | Declare/rename `$assertionsDisabled` field to match original |
| 2 | `fixAssertKeywordToExplicit` | Convert `assert` keyword to explicit check (avoid duplicate clinit) |
| 3 | `fixCHMAssertionInitOrder` | CHM assertion field must init before AtomicFieldUpdater |

### 3B. Synchronized Return Patterns (4 transforms)

Old javac (JDK 8-11) emits separate `MONITOREXIT + ARETURN` per branch inside
synchronized blocks. Modern javac merges them. Source must use if/else with
explicit returns to reproduce the two-exit pattern.

| # | Transform | Method |
|---|-----------|--------|
| 4 | `fixIsoObjectGetNewSyncReturn` | `IsoObject.getNew()` |
| 5 | `fixMultiStageBuildingCanBeDoneReturn` | `MultiStageBuilding$Stage.canBeDone()` |
| 6 | `fixServerLOSShouldWaitSyncReturn` | `ServerLOS.shouldWait()` |
| 7 | `fixSpNetworkPoolGetSyncReturn` | `ZomboidNetDataPool.get()` |

### 3C. Widening / Narrowing (1 transform)

Javac widening behavior differs when source uses explicit vs implicit conversions.

| # | Transform | What It Does |
|---|-----------|-------------|
| 8 | `fixIsoMovingObjectCompareToYWiden` | Widen `float` to `double` before comparison (F2D) |

### 3D. Lua Interop / Sandbox Patterns (2 transforms)

PZ's Lua bridge uses `KahluaTable` patterns that decompile into structurally
different code because Vineflower cannot distinguish Lua table access idioms.

| # | Transform | What It Does |
|---|-----------|-------------|
| 9 | `fixTableNameNullGuardPattern` | Null-guard on `tableName` rawget result |
| 10 | `fixSandboxFromToTable` | Sandbox `fromTable`/`toTable` guard clause inversion |

### 3E. Binary Compatibility (2 transforms)

These handle binary compatibility between recompiled classes and vanilla PZ classes.

| # | Transform | What It Does |
|---|-----------|-------------|
| 11 | `addBinaryCompatWarnings` | Add `@Deprecated` warnings for binary-incompatible methods |
| 12 | `fixSpecificFileErrors` (partial) | File-specific compilation fixes not attributable to Vineflower |

---

## Implementation Phases

### Phase 1: Low-Hanging Fruit (8 transforms, ~1 week)

Transforms where the Vineflower fix is straightforward or already partially implemented.

| Transform | Vineflower Component | Estimated Effort |
|-----------|---------------------|-----------------|
| `fixEmptySwitchExpressionCase` | `SwitchExpressionHelper` | 2h |
| `fixSwitchOnObject` | `SwitchStatement` | 2h |
| `fixMakeConcatWithConstants` | `InvocationExprent` | 4h |
| `fixVariableShadowsClassName` | `VarNamer` | 2h |
| `fixBooleanCanonicalization` | `ConstExprent` | 2h |
| `fixByteCounterVars` | `VarTypeProcessor` | 4h |
| `fixStringAssignmentNeedsCast` | `ExprProcessor` | 4h |
| `fixShortBufferPutMissingCast` | `ExprProcessor` | 2h |

**Verification**: After each fix, disable the PostDecompileTransform, rebuild,
and confirm 0 compile errors + no tier regression.

### Phase 2: Type Inference Pipeline (14 transforms, ~2 weeks)

The core type inference bugs. These cluster in `VarTypeProcessor` and
`GenericMethodInvocationProcessor`. Fixing the pipeline holistically will
eliminate multiple transforms at once.

| Transform Group | Count | Vineflower Component |
|----------------|-------|---------------------|
| Raw/Generic types (1A) | 12 | `GenericMethodInvocationProcessor`, `VarTypeProcessor` |
| instanceof scope (1C) | 2 | Pattern matching decompilation |

**Strategy**: Fix the generic type propagation first (biggest cluster), then
address instanceof scoping as a separate pass.

### Phase 3: Type Confusion (7 transforms, ~1 week)

Fix variable type inference for `int`/`boolean`, `File`/`String`, and
`Object`/specific-type confusions.

| Transform Group | Count | Vineflower Component |
|----------------|-------|---------------------|
| Type confusion (1B) | 7 | `VarTypeProcessor`, `ExprProcessor` |

### Phase 4: Control Flow (6 transforms, ~1 week)

Fix switch expression/statement handling and try-finally patterns.

| Transform Group | Count | Vineflower Component |
|----------------|-------|---------------------|
| Control flow (1D) | 6 | `SwitchExpressionHelper`, `FinallyProcessor` |

### Phase 5: Remaining Misc (2 transforms, ~2 days)

| Transform | Vineflower Component |
|-----------|---------------------|
| `fixDialogButtonAmbiguity` | Overload resolution |
| `fixAnnotationTypeCasts` | Annotation processing |

---

## Verification Protocol

For each eliminated transform:

```bash
# 1. Comment out the transform in PostDecompileTransforms.java
#    content = fixXxx(content);  →  // content = fixXxx(content);

# 2. Rebuild Vineflower (MUST force rebuild — Gradle caches JARs aggressively)
cd /home/jaidaken/optizomb/vineflower
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
# - Compilation: "Compiled successfully (2979 classes)" with 0 errors
# - Tiers: no regression in EXACT/STRUCTURAL/SORTED_MULTISET counts
# - Specific: the class(es) affected by the transform still compile and match
```

**Regression criteria**: A transform is successfully eliminated when:
1. The transform is commented out (not called)
2. Zero compile errors
3. Tier distribution is equal or better than baseline
4. The specific affected class(es) maintain their match tier

---

## Target End State

| Metric | Current | Target |
|--------|---------|--------|
| Total transforms | 69 + `addBinaryCompatWarnings` | ~34 |
| vineflower-fixable | 37 | **0** |
| game-specific-hack | 22 | 22 |
| java-limitation | 12 | 12 |
| Vineflower RTF coverage | Partial | Full (all 37 bugs fixed in fork) |

When complete:
- **34 permanent PostDecompileTransforms** (game-specific + java-limitation)
- **0 Vineflower bug transforms** (all fixed in fork)
- **Clean decompiled source** that compiles and matches original bytecode
- **Editable source** that developers can modify and recompile with confidence
- **Maintainable pipeline** where remaining transforms are documented and justified

---

## Progress Tracking

Update this section as transforms are eliminated.

| Phase | Transforms | Eliminated | Remaining |
|-------|-----------|------------|-----------|
| Phase 1: Low-Hanging Fruit | 8 | 0 | 8 |
| Phase 2: Type Inference | 14 | 0 | 14 |
| Phase 3: Type Confusion | 7 | 0 | 7 |
| Phase 4: Control Flow | 6 | 0 | 6 |
| Phase 5: Misc | 2 | 0 | 2 |
| **Total vineflower-fixable** | **37** | **0** | **37** |
