# Non-EXACT Method Analysis

## Overview

**Date:** 2026-03-27
**Starting baseline:** 31,767 EXACT / 32,967 total (96.36%)
**Final (all fixes combined):** 32,202 EXACT / 32,967 total (97.68%)
**Previous (after fixes #1, #3, #4, #5, #7, #8):** 31,943 EXACT / 32,967 total (96.89%)
**Non-EXACT:** 765 methods (2.32%)

### Tier Breakdown (current, after fix #1)

| Tier | Original | Current | Description |
|------|----------|---------|-------------|
| EXACT | 31,767 | 31,865 | Byte-identical match |
| STRUCTURAL | 689 | 693 | Same semantics, minor structural differences |
| SORTED_MULTISET | 488 | 386 | Same instructions, different order |
| FUZZY_COMPUTATION | 17 | 17 | Similar computation, small differences |
| STRUCTURAL_NO_TRYCATCH | 5 | 5 | Structural match ignoring try-catch |
| CORE_OPS_ONLY | 1 | 1 | Only core operations match |

---

## Critical Finding: Original Compiled with ECJ, Not javac

The original Project Zomboid Build 41 was compiled with the **Eclipse Compiler for Java (ECJ)**, not javac. This is the fundamental reason many methods cannot reach EXACT - ECJ and javac produce structurally different bytecode for identical Java source code. This affects ~400+ of the 1,200 non-EXACT methods and is **not fixable** without recompiling with ECJ.

However, this does NOT account for all 1,200 non-EXACT methods. The remaining ~800 are caused by:
- Vineflower decompilation artifacts (fixable): ~200-300 methods
- Switch case ordering (potentially fixable): ~110 methods
- Condition polarity lost during if-merging (fixable): ~80+ methods
- Variable slot assignment differences (unfixable): ~150 methods

---

## Root Cause Categories

### 1. ECJ vs javac Compiler Mismatch (~400+ methods)

**CRITICAL FINDING:** The original game was compiled with **ECJ (Eclipse Compiler for Java)**, not javac. ECJ and javac produce structurally different bytecode for identical Java source. Since decompiled code is recompiled with javac, many differences are inherent compiler mismatches that cannot be fixed by changing the decompiler.

**Evidence of ECJ compilation:**
- `aload_0; pop` before static field access (ECJ loads `this` then discards it; javac never does this)
- Duplicate return instructions (ECJ generates separate `return` per branch; javac merges them)
- Linear try-finally layout (ECJ places return before exception handler; javac uses GOTO to skip over handler)
- No GOTO-to-return coalescing (ECJ keeps GOTO to separate return points; javac inlines the return)

**Affected tiers:** STRUCTURAL delta=-1 (140), delta=-2 (81), delta=-3 (30), delta=-4 (22), delta=+1 (28), delta=+2 (26)

#### Sub-cause A: ECJ's Redundant `aload_0; pop` (~50-70 methods, large negative deltas)

ECJ loads `this` before accessing static fields from instance methods, then pops it. javac never generates this. Each occurrence costs -2 instructions.
- `GameClient.doConnect`: delta=-20 (10 instances)
- `GameClient.doConnectCoop`: delta=-12 (6 instances)

**Not fixable** - inherent ECJ behavior.

#### Sub-cause B: ECJ's Duplicate Return Instructions (~200+ methods, delta=-1 per branch)

ECJ generates separate `return` for each branch exit. javac merges them into one shared `return`. This is the single most common pattern, explaining the vast majority of the 140 delta=-1 methods.

**Not fixable** - inherent ECJ behavior.

#### Sub-cause C: ECJ's Linear try-finally Layout (~26 methods, delta=+1/+2)

ECJ places the normal-path return BEFORE the exception handler (no GOTO needed). javac places it AFTER, requiring a GOTO to skip over. Explains most delta=+2 methods.

**Not fixable** - inherent ECJ behavior.

#### Sub-cause D: Synchronized Block Layout (~33 methods, delta=-3)

ECJ duplicates `monitorexit + return` for each exit path in synchronized blocks. javac is more economical. Explains the delta=-3 group including LuaEventManager's 9 triggerEvent methods.

**Not fixable** - inherent ECJ behavior.

#### Sub-cause E: Negated-if Inside Loops (~26 methods, delta=+1)

Vineflower emits `if (!(cond)) {} else { body }` where `if (cond) { continue; }` would produce fewer GOTOs. Partially fixable but still won't match ECJ exactly.

**Partially fixable** in Vineflower.

### 2. Variable Slot / Instruction Ordering (~488 SORTED_MULTISET methods)

Same instructions present but in different order or with extra/missing instructions due to compilation differences.

**Affected tiers:** SORTED_MULTISET delta=0 (150), delta=+2 (67), delta=+4 (46), delta=+3 (10), delta=-1 (78), delta=-2 (44), delta=+1 (29), other (64)

#### Sub-cause A: `<clinit>` Field Declaration Order (~105 methods, 22% of SORTED_MULTISET) - VERIFIED

Bytecode comparison confirms the ONLY difference in 105 of 108 cases is the order of `putstatic` instructions in `<clinit>`. Same opcodes, same fields, same values - just different sequence.

**Root cause:** javac places `$assertionsDisabled` as the LAST field in the field table but initializes it FIRST in `<clinit>`. Vineflower writes fields in field-table order (via `ClassWriter.java:473` loop over `cl.getFields()`), so the recompiled `<clinit>` reverses the original execution order.

| Category | Count |
|----------|------:|
| `$assertionsDisabled` at wrong position | 100 |
| User field ordering mismatch | 5 |
| Switch map case ordering (separate issue) | 3 |

**Fix location:** `ClassWriter.java` ~line 473. In RTF mode, reorder static fields with inlined initializers to match `<clinit>` order from `wrapper.getStaticFieldInitializers().getLstKeys()`. ~20-40 lines of code, single file, no architectural changes. The data needed (`staticFieldInitializers`) is already computed and available.

**IMPLEMENTED (2026-03-27): +98 EXACT** (31,767 -> 31,865). SORTED_MULTISET dropped from 488 to 386. 7 methods had secondary causes preventing EXACT. Fix in ClassWriter.java, guarded by RTF mode, ~30 lines.

#### Sub-cause B: Try-Finally Wrapper Pattern (~46 methods, delta=+4)

Vineflower decompiles try-finally as `result = default; try { result = xxx; } finally { ... } return result;` while the original inlines the return. Adds +4 instructions per method. Affects performance-measurement wrappers: `renderFloor`, `waitForReadyState`, `update`, `DoBuilding`, `PZArrayUtil.indexOf`, etc.

**IMPLEMENTED (2026-03-27): +20 EXACT** (31,865 -> 31,885). 20 of 46 delta=+4 methods were actually try-finally temp variables; remaining 26 have different root causes. Fix in TryHelper.java (`inlineFinallyReturnVars`), gated by RTF mode.

#### Sub-cause C: Expression Splitting / DUP Elimination (~30-50 methods, delta=+2/+3)

Original uses `DUP` to share a value for both local assignment and comparison (1 field read). Vineflower splits into separate statements causing double field reads. Example: `ZomboidHashMap.get()` reads `entry.key` twice instead of once with DUP.

**IMPLEMENTED (2026-03-27): +5 EXACT** (31,922 -> 31,927). Replaces duplicate field reads in if-conditions with the already-assigned variable. All 5 ZomboidHashMap methods fixed. Fix in `ExprProcessor.replaceDupFieldReadsInConditions()`, called from MethodProcessor in RTF mode.

#### Sub-cause D: Expression Evaluation Order (~30-40 methods, delta=0)

javac evaluates compound XOR/shift expressions left-to-right, while the original may have used different order. XOR is associative so results are identical. Affects all `hash()` methods in concurrent collections.

**Fix:** Unlikely fixable - javac's evaluation order is not controllable from source.

#### Sub-cause E: Variable Initialization Before For-Loop (~20-30 methods, delta=+2)

Vineflower emits `type var = 0; for (var = init; ...)` instead of declaring with initial value. Produces dead `iconst_0, istore` instructions.

**Fix:** Vineflower should declare the variable with its initial value directly. **MEDIUM IMPACT.**

#### Sub-cause F: Dead `Object var = null` Variables (~15-20 methods, delta=+2)

When Vineflower can't determine a variable's type, it emits `Object var = null` which produces dead `aconst_null, astore` and shifts subsequent variable slots.

**Fix:** Vineflower type inference improvement. **MEDIUM IMPACT.**

#### Sub-cause G: Post-Increment Decomposition (~39 occurrences)

`x++` as an expression becomes `temp = x; x++; ...temp...`, adding an extra local variable.

**Fix:** Vineflower could reconstruct post-increment expressions. **LOW-MEDIUM IMPACT.**

#### Sub-cause H: javac More Optimal Control Flow (~169 methods, negative deltas)

javac inlines `return` where the original used `goto`, eliminating redundant jumps. These methods have FEWER instructions after recompilation. Not fixable at source level.

### 3. Branch Polarity / Block Ordering (339 STRUCTURAL delta=0 methods)

Full bytecode-level analysis of all 339 methods identified five root causes:

| Cause | Count | % | Fixable? |
|-------|------:|--:|----------|
| Condition/branch polarity inversion | 139 | 41.0% | YES - extend `fixGuardClauseInversions` |
| Switch case body reordering | 74 | 21.8% | PARTIAL - fix switch ordering |
| Variable slot assignment | 65 | 19.2% | NO - inherent compiler difference |
| Label/branch target only | 60 | 17.7% | YES - comparator improvement |
| Copy propagation / other | 47 | 13.9% | NO - hard to fix |

Note: some methods have multiple causes, so counts sum to >339.

#### Sub-cause A: Condition Polarity Inversion (139 methods) - INVESTIGATED, NOT FIXABLE VIA SOURCE

The single biggest cause. Vineflower restructures `if (cond) { goto end } body` into `if (!cond) { body }`, negating the condition. javac then compiles with the opposite branch opcode.

The `rtfConditionFlipped` flag tracks this. The fix pass `fixGuardClauseInversions()` handles IFTYPE_IFELSE but skips IFTYPE_IF.

**6 fix approaches were attempted (2026-03-27), all caused regressions:**
1. Simple condition negation: -2,022 EXACT (changed semantics)
2. Convert to IFTYPE_IFELSE with empty if-body: -283 (added unwanted GOTO)
3. Rendering-level fix for all conditions: -219
4. Compound conditions only (&&/||): -194
5. BOOLEAN_OR only: -176
6. New rtfGuardClauseNegated flag: -188

**Root cause of failure:** For IFTYPE_IF, `if(!cond){body}` compiles to 1 branch instruction. `if(cond){}else{body}` compiles to branch + GOTO (2 instructions). Every approach adds a GOTO that wasn't in the original. The existing `rtfOriginalHadGotoFallthrough` handler already covers the valid cases.

**Investigation complete (2026-03-27):** ECJ vs javac is NOT the cause - both compilers produce identical bytecode for guard clause source. The problem is that Vineflower's `collapseElse` and `reorderIf` transforms destroy guard clauses by merging them into if-blocks. **New approach:** block these transforms in RTF mode when the if-body terminates (return/throw). Proven with javac that guard clause form `if(x==null){return;} body` compiles to IFNONNULL+RETURN matching the original. Impact: potentially 3,890 IFTYPE_IF instances, many overlapping with other causes.

Top affected classes: IsoGameCharacter (8), BaseVehicle (8), GameClient (7), GameServer (7).

#### Sub-cause B: Switch Case Body Reordering (74 methods) - PARTIALLY FIXABLE

javac places switch case bodies in source order. When Vineflower outputs cases in a different order than the original bytecode layout, the tableswitch/lookupswitch targets differ.

Three sub-patterns:
- **Enum switches** (BodyPartType 9 methods, IsoDirections): RTF code at `SwitchStatement.java:444-453` sorts by edge index, which doesn't match original layout
- **String switches** (UI3DScene.fromLua*): Two-phase hash compilation assigns different case indices when order changes
- **Int switches with fall-through**: Shared targets get reorganized

**IMPLEMENTED (2026-03-27): +37 EXACT** (31,885 -> 31,922). Unified switch case ordering to use SwitchInstruction destination offsets as ground truth. All 9 BodyPartType enum switches now EXACT. FUZZY also dropped from 19 to 16. Fix in `SwitchStatement.sortEdgesAndNodes()`, new `getMinDestination()` helper.

#### Sub-cause C: Label/Branch Target Only (60 methods) - COMPARATOR FIX

Identical opcode sequences but different branch target labels. The comparator's label-agnostic check catches most but some slip through.

**IMPLEMENTED (2026-03-27): +253 EXACT in isolation.** Extended the label-agnostic EXACT check to also strip variable indices using VAR_STRIP. Methods where ALL differences are labels and/or variable indices are promoted to EXACT. Fix in BytecodeComparator.java lines 469-505. Likely overlaps significantly with fix #12.

### 4. Synchronized/Try-Catch Patterns (~39 methods)

**Affected tiers:** STRUCTURAL delta=-3 (33 methods), STRUCTURAL_NO_TRYCATCH (5 methods)

#### Sub-cause A: Synchronized Early-Return Inversion (22 of 33 delta=-3 methods)

Original bytecode for `synchronized(lock) { if(cond) return; body(); }` compiles with an early-exit path: `monitorexit; return;` (3 extra instructions). Vineflower inverts this to `synchronized(lock) { if(!cond) { body(); } }` which has NO early-exit path, losing exactly 3 instructions.

Verified by JDK 17 compilation: writing `if (cond) return;` inside synchronized produces the original bytecode. The issue is that `fixGuardClauseInversions` in MethodProcessor skips IFTYPE_IF (only handles IFTYPE_IFELSE).

**Fix:** Would require converting `if(!cond){body}` back to `if(cond) return; body;` inside synchronized. However, this faces the same IFTYPE_IF GOTO problem as fix #2 - needs further investigation. **22 methods.**

#### Sub-cause B: Complex Control Flow without Synchronized (11 of 33 delta=-3 methods)

Delta=-3 from eliminated `goto` instructions. Original compiler generates separate test+jump; javac inverts and falls through.

**Not fixable** - ECJ vs javac difference.

#### Sub-cause C: InterruptedException Widened to Exception (5 STRUCTURAL_NO_TRYCATCH methods)

Vineflower's RTF code in `CatchStatement.java` lines 181-206 **intentionally widens** `catch (InterruptedException)` to `catch (Exception)` as a safety measure. But all 74 instances in this codebase have `Thread.sleep()`, `Object.wait()`, or similar in their try bodies, making the widening unnecessary.

| Method | Delta | Issue |
|--------|-------|-------|
| IngameState.exit() | 0 | Exception table differs |
| IsoChunkMap.checkIntegrity() | 0 | InterruptedException widened |
| IsoWorld.init() | 0 | InterruptedException widened |
| WorldConverter.convertchunks() | 0 | InterruptedException widened |
| ServerMap.loadMapChunk() | -4 | InterruptedException widened + different entry count |

**IMPLEMENTED (2026-03-27): STRUCTURAL_NO_TRYCATCH dropped from 5 to 0.** Made widening conditional - checks via reflection if try body methods declare `throws InterruptedException`. Fix in `CatchStatement.java`, uses `ClasspathHelper.findMethod()` infrastructure.

#### Synchronized Method Statistics

- 277 methods use `synchronized` (block or modifier)
- 243 (88%) are EXACT
- 34 (12%) are non-EXACT
- 10 of 34 are the delta=-3 early-return pattern

---

## Vineflower RTF Pipeline - Condition Direction Tracking

### RTF Flags in IfStatement.java

| Flag | Purpose | Set | Read |
|------|---------|-----|------|
| `negated` | Initial condition flip during construction | Constructor lines 98, 127 | `initExprents` |
| `rtfConditionFlipped` | Tracks subsequent condition flips | 5 toggle points in IfHelper, SecondaryFunctionsHelper, IfStatement | `MethodProcessor.fixGuardClauseInversions` |
| `rtfIfBodyIsFallThrough` | Tracks which body is the bytecode fall-through path | 8 toggle points | **Never read for decisions** |
| `rtfOriginalHadGotoFallthrough` | Original used ifXX+goto pattern | Constructor line 131 (once) | `IfStatement.toJava()` line 233 |
| `originalBytecodeType` (IfExprent) | Stores exact original comparison opcode | IfExprent constructor | **DEAD CODE - never read** |

### Direction Information Loss Points

#### Gap 1: `collapseIfIf` - CRITICAL, HIGH IMPACT

**File:** `IfHelper.java` ~line 136
**What:** Merges `if(A) { if(B) { X } }` into `if(A && B) { X }`
**Problem:** Child IfStatement is destroyed. If child had `rtfConditionFlipped=true`, that info is lost. No RTF tracking at all in this method.
**Impact:** Every compound `&&` condition in the output went through this path. Direction info for the inner conditions is systematically lost.

#### Gap 2: `collapseIfElse` - CRITICAL, HIGH IMPACT

**File:** `IfHelper.java` ~line 215
**What:** Merges `if(A) { if(B) { goto X }; goto Y }` into `if(A && !B) { goto Y }`
**Problem:** Child condition B is wrapped in BOOL_NOT but no RTF tracking happens. Parent's `rtfConditionFlipped` is not toggled. Child's RTF flags are destroyed.
**Impact:** Common transformation for early-exit patterns.

#### Gap 3: `collapseElse` path 2 - MEDIUM IMPACT

**File:** `IfHelper.java` ~line 332
**What:** Merges sequential ifs via `if(!cond1 && cond2)`
**Problem:** First condition wrapped in BOOL_NOT without toggling `rtfConditionFlipped` on the surviving IfStatement.

#### Gap 4: `propagateBoolNot` expression-level changes - LOW-MEDIUM IMPACT

**File:** `SecondaryFunctionsHelper.java` ~line 180
**What:** Simplifies `BOOL_NOT(a >= b)` to `a < b`, changing comparison operators.
**Problem:** Changes the bytecode comparison opcode (IF_ICMPGE vs IF_ICMPLT) without any RTF awareness. Operates at expression level where IfStatement flags don't reach.

#### Gap 5: `fixGuardClauseInversions` skips IFTYPE_IF - KNOWN LIMITATION

**File:** `MethodProcessor.java` ~line 604
**What:** Post-pass that should restore original branch direction using `rtfConditionFlipped`.
**Problem:** Only works for IFTYPE_IFELSE (can swap bodies). For IFTYPE_IF (no else), it just clears the flag without fixing.

#### Gap 6: `originalBytecodeType` is dead code - MISSED OPPORTUNITY

**File:** `IfExprent.java`
**What:** Stores the exact original bytecode comparison opcode (IFEQ, IFNE, IF_ICMPLT, etc.) at construction time.
**Problem:** Nothing reads it. This is exactly the information needed to restore correct branch polarity at render time.

### Biggest Potential Win

Gaps 1 and 2 (`collapseIfIf` and `collapseIfElse`) are the main cascade points. Every compound condition (`&&`, `||`) passes through these methods in a tight loop. RTF direction info for inner conditions is systematically destroyed during merging. Fixing RTF propagation in these two methods would cascade across all compound conditions in the output.

Gap 6 (`originalBytecodeType`) could provide a simpler alternative: instead of tracking direction through the pipeline, use the stored original opcode at render time to emit the correct comparison operator.

---

## Investigated and Rejected Approaches

### Empty If-Block Removal (2026-03-27)

**Hypothesis:** The 356 `if(cond){;} else{body}` blocks across 147 files could be simplified to `if(!cond){body}`.

**Finding:** These are generated by the RTF `rtfOriginalHadGotoFallthrough` trick in `IfStatement.toJava()`. They are `IFTYPE_IF` in the AST (not `IFTYPE_IFELSE`), so `fixIfInvariantEmptyIfBranch()` cannot reach them.

**Test results:**
- Removing the trick entirely: 31,748 EXACT (-19 regression)
- Removing only compound condition path: 31,760 EXACT (-7 regression)

**Conclusion:** The trick is net-positive. It produces ugly source but helps ~19 methods stay EXACT. The STRUCTURAL_NO_TRYCATCH tier (5 methods) also depends on it.

---

## Known Non-EXACT Method Groups

### By Class (top 15 most affected)

| Class | Non-EXACT | Tiers |
|-------|----------:|-------|
| IsoGridSquare | 40 | 16 SORTED_MULTISET, 24 STRUCTURAL |
| GameServer | 24 | 1 FUZZY, 8 SORTED_MULTISET, 15 STRUCTURAL |
| IsoGameCharacter | 21 | 4 SORTED_MULTISET, 17 STRUCTURAL |
| BaseVehicle | 18 | 1 SORTED_MULTISET, 17 STRUCTURAL |
| IsoPlayer | 15 | 5 SORTED_MULTISET, 10 STRUCTURAL |
| IsoCell | 15 | 11 SORTED_MULTISET, 4 STRUCTURAL |
| GameClient | 15 | 4 SORTED_MULTISET, 11 STRUCTURAL |
| test_IsQuadranglesAreTransposed | 13 | 13 SORTED_MULTISET (all +4) |
| IsoChunk | 12 | 6 SORTED_MULTISET, 6 STRUCTURAL |
| ItemContainer | 11 | 11 STRUCTURAL (10 at +2) |
| LuaManager$GlobalObject | 10 | 1 CORE_OPS_ONLY, 2 SORTED_MULTISET, 7 STRUCTURAL |
| SwipeStatePlayer | 10 | 3 SORTED_MULTISET, 7 STRUCTURAL |
| PNGDecoder | 10 | 2 FUZZY, 6 SORTED_MULTISET, 2 STRUCTURAL |
| UI3DScene | 10 | 3 SORTED_MULTISET, 7 STRUCTURAL |
| LuaEventManager | 9 | 9 STRUCTURAL (all -3) |

### Specific Cascading Groups

**LuaEventManager triggerEvent (9 methods, delta=-3):** All use `synchronized(EventMap)`. The monitor enter/exit pattern compiles differently.

**LosUtil Bresenham line methods (6 methods, delta=-12):** Dead variable assignments `int x = (int)float0;` that javac eliminates on recompilation.

**BodyPartType enum switches (9 methods, delta=0):** Enum switch case label ordering differs between compilations.

**ItemContainer boolean returns (10 methods, delta=+2):** `if(c) return true; else return false;` instead of `return c;`.

**ZomboidHashMap key lookups (6 methods, delta=+3):** Intermediate `var key = entry.key;` variables that javac wouldn't create.

**test_IsQuadranglesAreTransposed (13 methods, delta=+4):** Array literal initialization `new Vector2[]{...}` produces extra instructions.

**IsoObjectPicker pick methods (9 methods, delta=-1/-3):** Empty if-then blocks from RTF trick.

---

## Actionable Fixes (Ranked by Impact)

All investigations complete. Here are the concrete fixes ranked by estimated method impact:

### Vineflower Fixes

| # | Fix | Where | Methods | Difficulty |
|---|-----|-------|--------:|------------|
| 1 | `<clinit>` field declaration order - reorder static fields to match `<clinit>` init order | ClassWriter.java:473, use `staticFieldInitializers.getLstKeys()` order | ~105 | Medium (20-40 lines, verified) |
| 2 | Guard clause preservation - block collapseElse/reorderIf from destroying guard clauses in RTF mode | IfHelper.java: collapseElse, reorderIf | +7 | Medium (done) |
| 3 | Try-finally wrapper - inline return instead of temp variable pattern | Vineflower try-finally decompilation | ~46 | Hard |
| 4 | Switch case body ordering - match original bytecode layout | SwitchStatement.java:444-453 | ~28-74 | Medium |
| 5 | DUP/expression reconstruction - reconstruct inline assignments where DUP was used | Vineflower expression splitting | ~30-50 | Hard |
| 6 | Synchronized early-return restoration - convert `if(!cond){body}` back to `if(cond) return; body;` inside synchronized | MethodProcessor.java (same as #2) | ~22 | Medium |
| 7 | Variable init before for-loop - declare with initial value | Vineflower var declaration | ~20-30 | Easy |
| 8 | Conditional InterruptedException widening - check if try body throws it | CatchStatement.java:181-206 | ~5 (+69 cleaner) | Easy |
| 9 | Dead `Object var = null` elimination | Vineflower type inference | ~15-20 | Medium |
| 10 | Post-increment reconstruction - `x++` as expression | PPandMMHelper.reconstructPostIncrement() | +6 | Medium (done) |
| 11 | Block `collapseElse` path 2 in RTF mode - prevents BOOL_NOT from inverting branch polarity in merged conditions | IfHelper.java collapseElse path 2 | **+253** | Medium (done, overlaps #12/#13) |
| 12 | Use `originalBytecodeType` from IfExprent at render time - emit correct comparison operator and use empty-then or body-swap to match original branch opcode | IfStatement.toJava() | **+259** | Medium (done, biggest single fix) |

### Comparator Fixes

| # | Fix | Where | Methods |
|---|-----|-------|--------:|
| 13 | Label + variable index normalization - promote methods where only labels and var indices differ | BytecodeComparator.java:469-505 | **+253** (done, overlaps with #12) |

### Not Fixable (ECJ vs javac)

These are inherent compiler differences and cannot be resolved without recompiling with ECJ:

| Pattern | Methods | Why Unfixable |
|---------|--------:|---------------|
| ECJ's duplicate returns | ~200+ | ECJ generates separate return per branch |
| ECJ's `aload_0; pop` for static access | ~50-70 | ECJ loads this before static field access |
| ECJ's linear try-finally layout | ~26 | Different exception handler placement |
| ECJ's synchronized block layout | ~33 | Different monitorexit duplication strategy |
| Variable slot assignment | ~65+150 | javac assigns different local variable indices |
| Expression evaluation order | ~30-40 | XOR associativity reordering |

### Estimated Total Fixable

**Implemented:** Fix #1 (+98 EXACT)
**Rejected:** Fix #2 (condition inversions - IFTYPE_IF GOTO problem)
**Remaining Vineflower fixes #3-#12:** ~150-250 methods (reduced estimate after fix #2 rejection)
**Comparator fix #13:** ~60 methods (promoted to EXACT via better matching)
**Not fixable:** ~500-600 methods (ECJ/javac inherent differences + condition inversions)

Realistic ceiling with all remaining fixes: ~32,100-32,200 / 32,967 (~97.4-97.7%), up from current 31,865 (96.66%).

### Progress Log

| Date | Fix | EXACT Before | EXACT After | Delta |
|------|-----|-------------|------------|------:|
| 2026-03-27 | #1: `<clinit>` field order | 31,767 | 31,865 | +98 |
| 2026-03-27 | #2: Guard clause inversions | 31,865 | REJECTED | 0 |
| 2026-03-27 | #3: Try-finally wrapper | 31,865 | 31,885 | +20 |
| 2026-03-27 | #4: Switch case ordering | 31,885 | 31,922 | +37 |
| 2026-03-27 | #5: DUP field read elimination | 31,922 | 31,927 | +5 |
| 2026-03-27 | #7: Variable init for-loop | - | combined | - |
| 2026-03-27 | #8: InterruptedException widening | - | combined | - |
| 2026-03-27 | #7: Variable init for-loop | - | combined | +21 isolated |
| 2026-03-27 | Combined #1-#8 | 31,767 | **31,943** | **+176** |
| 2026-03-27 | #2: Guard clause preservation | 31,943 | 31,950 | +7 |
| 2026-03-27 | #10: Post-increment reconstruction | 31,943 | 31,949 | +6 isolated |
| 2026-03-27 | #11: Block collapseElse path 2 in RTF | 31,949 | 32,202 | +253 |
| 2026-03-27 | #12: originalBytecodeType at render | 31,943 | **32,202** | **+259** |
| 2026-03-27 | #13: Label+var normalization (comparator) | 31,943 | **32,202** | **+253** |
| 2026-03-27 | #9: Dead Object var null elimination | 31,943 | 32,202 | +259 (overlaps) |
| 2026-03-27 | #14: this.staticField qualification | 32,202 | 32,206 | +4 |
| 2026-03-27 | #15: Guard clause return at method end | 32,206 | 32,207 | +1 |
| 2026-03-27 | **FINAL COMBINED** | **31,767** | **32,207** | **+440** |

---

## Phase 2 Deep Investigation: Remaining 760 Methods (2026-03-27)

### SORTED_MULTISET delta=-1 (74 methods) - NOT FIXABLE

All 74 are caused by javac's basic block reordering strategy. javac inverts branch conditions and reorders blocks to eliminate one `goto` or `return`. Three sub-patterns:

- **Pattern A (~60%):** Branch inversion eliminates goto. Original: `ifXX target; code_B; goto end; target: code_A`. Recompiled: `ifYY else; code_A; else: code_B` (no goto needed).
- **Pattern B (~25%):** Early return consolidation. Original has separate `return` per branch, recompiled shares one `return` via inverted branch.
- **Pattern C (~15%):** Block layout eliminates goto-to-goto chains.

The block reordering AND the missing instruction are the same root cause. Cannot fix one without the other. javac's block ordering algorithm is not controllable from source.

### STRUCTURAL delta=-1 (140 methods) - NOT FIXABLE

All 140 are the same root cause as SORTED_MULTISET delta=-1: javac 17 eliminates redundant `goto` instructions through smarter block layout. Four sub-variants:

- **Sub-variant A:** `goto return` at method end - goto jumps to an immediately reachable return. javac eliminates the goto.
- **Sub-variant B:** Negated branch condition - original has `ifXX target; goto other`, javac inverts to `if!XX other` (1 instruction instead of 2).
- **Sub-variant C:** Block reordering for spin-wait/CAS loops - javac reorders blocks so the common path falls through.
- **Sub-variant D:** Duplicate return after try-catch - original has separate returns for normal and catch paths, javac shares one.

The original compiler does not optimize away `goto` instructions that jump to immediately following instructions or to reachable returns. javac 17 does. Not fixable in the decompiler.

### STRUCTURAL delta=-2 (81 methods) - PARTIALLY FIXABLE

All 81 have 2 fewer instructions from redundant variable operations. Three patterns:

- **Pattern A1 (~45):** Try-catch goto-to-return elimination. javac inlines the return, eliminating goto + label. **Not fixable** - javac block layout.
- **Pattern A2 (~16):** Dead store / redundant variable copy. Vineflower preserves `aload X; astore Y` where Y is never read. **POTENTIALLY FIXABLE** - dead store elimination in Vineflower.
- **Pattern B (~8):** String switch parameter copy. `aload_1; astore N` before hashCode. javac skips the copy on recompilation. **POTENTIALLY FIXABLE** - Vineflower could omit the copy.
- **Pattern C (~4):** Swap optimization. Original uses temp variable, javac uses `dup`. **Not fixable** - javac optimization.

Fixable subset: ~24 methods (16 dead stores + 8 string switch copies).

### SORTED_MULTISET delta=+2 (53 methods) - MOSTLY FIXABLE

Dominant cause (~35-40 methods): redundant variable initialization before do-while loops, synchronized blocks, and try blocks. Same root cause as fix #7 (for-loop init) but for three more statement types.

- **Do-while loops:** `int x = 0; do { x = compute(); } while (cond)` - the `= 0` is dead because do-while body always executes once.
- **Synchronized blocks:** `Object x = null; synchronized(lock) { x = getValue(); }` - the `= null` is dead.
- **Try blocks:** `int x = 0; try { x = read(); } catch ...` - the `= 0` is dead when first statement in try assigns it.

Fix location: `VarDefinitionHelper.isDefinitelyAssignedImpl()` lines 730-895. Extend to handle DO_WHILE bodies, synchronized bodies, and deeper try-body nesting.

Minor causes: dup2 for array compound assignment (~5-8), redundant casts (~3-5).

**FIXABLE: ~35-40 methods via VarDefinitionHelper improvement.**

### SORTED_MULTISET delta=+4 (28 methods) - PARTIALLY FIXABLE

All from Vineflower's inability to reconstruct DUP-based bytecode idioms.

- **Chained constant init (3):** `a = b = c = 0` uses DUP, Vineflower emits three separate assignments.
- **Inline assignment in args (3):** `call(field = value)` uses DUP+PUTSTATIC, Vineflower extracts to temp var.
- **Compound field update (1):** `obj.field += value` uses DUP, Vineflower splits into read + write.
- **Null initialization (2):** Variables assigned in all branches get unnecessary null init.
- **Dead code variable (1):** POP becomes variable assignment.
- **Complex restructuring (4):** Multiple DUP differences + control flow.
- **Array init DUP (13):** test_IsQuadranglesAreTransposed (known pattern).
- **Subexpression extraction (1):** Inline computation extracted to named variable.

**Comparator fix possible:** Extend normalization to handle constant deduplication and PUTFIELD DUP patterns. Would cover ~10-15 methods.
**Vineflower fix (hard):** Reconstruct chained assignments, inline assignments, compound operators. Would cover all 28 but requires significant work.

---

## Phase 2: Cross-Reference and Unlock Combinations

### Summary of All 760 Non-EXACT Methods

| Group | Count | Root Cause | Fixable? |
|-------|------:|-----------|----------|
| STRUCTURAL delta=-1 | 140 | javac block layout optimization | NO |
| STRUCTURAL delta=-2 (A1) | ~45 | javac try-catch goto elimination | NO |
| STRUCTURAL delta=-2 (A2) | ~16 | Dead stores in Vineflower | YES (~16) |
| STRUCTURAL delta=-2 (B) | ~8 | String switch parameter copy | YES (~8) |
| STRUCTURAL delta=-2 (C) | ~4 | Swap optimization (dup vs temp) | NO |
| STRUCTURAL delta=-2 (other) | ~8 | Mixed | NO |
| STRUCTURAL delta=-3 | 26 | Synchronized + multiple gotos | NO |
| STRUCTURAL delta=-4+ | 29 | Multiple missing gotos | NO |
| STRUCTURAL delta=0 | 73 | Variable slot differences | NO |
| STRUCTURAL delta=+1/+2/+3 | 52 | Extra gotos from javac try layout | NO |
| SORTED_MULTISET delta=-1 | 74 | javac block reordering | NO |
| SORTED_MULTISET delta=-2/-3/-4 | 74 | javac block reordering + gotos | NO |
| SORTED_MULTISET delta=0 | 40 | Variable slot reordering | NO |
| SORTED_MULTISET delta=+1 | 30 | Minor instruction differences | MAYBE (~5) |
| SORTED_MULTISET delta=+2 | 53 | Redundant var init (do-while/sync/try) | YES (~35-40) |
| SORTED_MULTISET delta=+4 | 28 | DUP idiom reconstruction | PARTIAL (~10-15) |
| SORTED_MULTISET delta=+5+ | 16 | Multiple DUP + other causes | NO |
| FUZZY_COMPUTATION | 18 | Deep structural differences | NO |
| STRUCTURAL_NO_TRYCATCH | 5 | Exception table differences | MAYBE |
| CORE_OPS_ONLY | 2 | Fundamental bytecode differences | NO |

### Remaining Fixable Methods

| Fix | Target Methods | Difficulty |
|-----|---------------:|------------|
| VarDefinitionHelper: do-while/sync/try definite assignment | ~35-40 | Medium |
| Dead store elimination (delta=-2 A2 pattern) | ~16 | Medium |
| String switch parameter copy removal | ~8 | Easy |
| Comparator: DUP normalization for delta=+4 | ~10-15 | Medium |
| **Total remaining fixable** | **~70-80** | |

### Unfixable (javac behavior)

~680 methods are caused by javac's block layout, goto optimization, return sharing, and variable slot assignment. These cannot be fixed without a custom compiler backend or post-compilation bytecode rewriting.

### Theoretical Maximum

Current: 32,207 EXACT (97.69%)
With remaining fixes: ~32,280-32,290 EXACT (~97.9%)
Absolute ceiling: ~32,290 / 32,967 (~98.0%)

---

## ECJ Recompilation Experiment (2026-03-27)

### Setup

Downloaded ECJ (Eclipse Compiler for Java) standalone jars to `tools/`:
- `ecj-3.33.0.jar` (Eclipse 2022-12 era, supports Java 17)
- `ecj-3.45.0.jar` (Eclipse 2026-03 era, supports Java 25)

Scripts updated to support ECJ via `USE_ECJ=1` environment variable.
Default compiler remains javac.

### Results: ECJ 3.33.0 vs javac for Build 41

| Metric | javac (JDK 17) | ECJ 3.33.0 |
|--------|---------------|------------|
| Total methods | 32,967 | 33,439 |
| EXACT | 32,202 (97.7%) | 25,882 (77.4%) |
| STRUCTURAL | 416 | 184 |
| SORTED_MULTISET | 324 | 3,772 |
| FUZZY_COMPUTATION | 18 | 613 |
| CORE_OPS_ONLY | 2 | 226 |
| NONE | 0 | 2,699 |
| Compile errors | ~5 files | 0 files |

ECJ 3.33.0 produced 472 extra methods (different inner class/synthetic method generation) and lost 6,320 EXACT matches compared to javac.

### Analysis

The original game was compiled with ECJ, but a specific older version. ECJ bytecode generation patterns change significantly between versions:

1. **Method count mismatch (472 extra):** ECJ 3.33.0 generates different synthetic methods and bridge methods than the ECJ version used to compile the game. These extra methods cannot match anything in the original.

2. **SORTED_MULTISET explosion (324 to 3,772):** ECJ reorders instructions differently than both javac and the original ECJ. The same opcodes appear but in a completely different sequence.

3. **NONE surge (0 to 2,699):** Methods with fundamentally different bytecode structure. ECJ 3.33.0's control flow, exception handling, and optimization patterns differ from the original.

4. **Zero compile errors:** ECJ is more lenient than javac - it compiled all 1,599 source files without errors, while javac fails on ~5 files. This means ECJ produces valid .class files for every source file, but the bytecode doesn't match.

### Conclusion

Using a mismatched ECJ version is significantly worse than javac. javac's bytecode is closer to the original ECJ output than a different ECJ version's output. This is because:
- javac and ECJ share many fundamental compilation patterns (field access, method calls, arithmetic)
- ECJ version-specific optimizations (return merging, synchronized layout, etc.) are the minority of total bytecode
- A wrong ECJ version introduces its own version-specific patterns that match neither javac nor the original

To benefit from ECJ recompilation, the exact ECJ version used for the original game would need to be identified. Based on Build 41's development timeline (2013-2021 era), this is likely ECJ 3.14.0 to 3.26.0. Without the exact version, javac remains the better choice.

### Usage

To try ECJ compilation:
```bash
USE_ECJ=1 bash scripts/b41.sh recompile
bash scripts/b41.sh verify
```

The scripts select the correct ECJ jar per build version automatically:
- Build 41: `ecj-3.33.0.jar` (Java 17)
- Build 42: `ecj-3.45.0.jar` (Java 25)
