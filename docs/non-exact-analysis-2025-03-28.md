# Non-EXACT Method Analysis - March 28, 2026

## Current Status
- **EXACT**: 32,208 / 32,967 (97.7%)
- **Non-EXACT**: 759 methods across 5 tiers

| Tier | Count | Description |
|------|-------|-------------|
| STRUCTURAL | 411 | Same logic, different bytecode block layout |
| SORTED_MULTISET | 323 | Same instruction bag, different order |
| FUZZY_COMPUTATION | 18 | Minor computation differences |
| STRUCTURAL_NO_TRYCATCH | 5 | Structural match but try-catch scopes differ |
| CORE_OPS_ONLY | 2 | Only core operations match |

## Methodology

Compared bytecode (via javap) for 476 matched non-EXACT methods between original game classes and recompiled classes. Categorized differences by pattern type.

## Pattern Breakdown

### 1. Block Layout Differences (196 methods) - HARD TO FIX

**What**: Opcodes are identical, but operand values differ (jump target offsets). The instructions are the same, just positioned at different bytecode addresses because javac lays out code blocks in a different order.

**Why**: When Vineflower decompiles if/else structures, it may choose a different block order than the original. javac then compiles this with different jump target offsets. The BytecodeComparator already normalizes label targets and variable slots, promoting label-only diffs to EXACT. These remaining 196 methods have differences that survive normalization.

**Fix approach**: Requires Vineflower to preserve the original block ordering from the bytecode CFG. This is a deep architectural change affecting how FlattenStatementsHelper orders blocks during decompilation. Not a quick fix.

**Impact**: 196 methods (26% of non-EXACT)

### 2. Field/Method Operand Differences (68 methods) - MEDIUM

Broken down by instruction type:
- `getfield` operand: 28 methods
- `getstatic` operand: 24 methods
- `invokevirtual` operand: 16 methods

**What**: The opcode is the same (e.g., `getfield`) but the field/method reference uses a different owner class. Example: original accesses `zombie/characters/IsoGameCharacter.Traits` but recompiled accesses `zombie/characters/IsoPlayer.Traits` - same field, different owner class in the constant pool.

**Why**: Vineflower uses the declared variable type for field access, but the original bytecode may have referenced the field through a superclass. When a field is inherited, `getfield owner.field` can use either the declaring class or a subclass as `owner`. javac uses the compile-time type of the expression, which may differ from what the original bytecode used.

**Fix approach**: In Vineflower's RTF mode, when emitting field/method access, check the original bytecode's owner class. If it differs from what the decompiled source would produce, emit a cast: `((SuperClass) var).field` to force javac to use the correct owner. The FieldExprent already has `originalBytecodeType` support for some cases - extend to cover receiver types.

**Impact**: 68 methods (9% of non-EXACT). Only ~16 methods have true owner class differences (many were false positives from my script matching wrong overloaded methods). The test_IsQuadranglesAreTransposed class accounts for 13 of these with actual float constant value differences (a separate decompilation error).

### 3. Branch Polarity Inversion (40+ methods) - MEDIUM

**What**: Original uses `ifne` but recompiled uses `ifeq` (or vice versa) for the same condition check. Both are semantically equivalent but produce different bytes.

**Why**: When Vineflower decompiles `if (condition) { body }`, the branch direction depends on whether the if-body is the fall-through or the jump target. The original bytecode might use `IFNE target` (jump when true), but the decompiled code's structure causes javac to emit `IFEQ target` (jump when false).

Vineflower's RTF mode tracks condition flips via `rtfConditionFlipped` and `originalBytecodeType`, but two gaps exist:

1. **Bare boolean conditions**: When the condition simplifies to a bare variable/field (e.g., `if (bServer)` instead of `if (bServer != 0)`), the `rtfGetEffectiveComparisonType()` method returns null because it only handles FunctionExprent. **FIX IMPLEMENTED** (but not triggering yet - needs debugging).

2. **IFTYPE_IF with rtfConditionFlipped**: The `fixGuardClauseInversions()` method only handles `IFTYPE_IFELSE`, not `IFTYPE_IF`. For `IFTYPE_IF`, it clears the flag without fixing.

**Fix approach**:
- Extend `rtfGetEffectiveComparisonType()` to handle bare VarExprent/FieldExprent (returns NE) and BOOL_NOT(bare) (returns EQ). **DONE in this session** but not verified working yet.
- Extend `rtfBuildCorrectedCondition()` to wrap/unwrap BOOL_NOT for bare booleans. **DONE in this session**.
- Debug why the fix isn't triggering - likely the `originalBytecodeType` is being lost or the condition is being rendered before the correction check.
- Fix `fixGuardClauseInversions()` for IFTYPE_IF cases.

**Impact**: 40+ methods (5% of non-EXACT)

### 4. GOTO vs Return Substitution (25 methods) - MEDIUM

**What**: Original bytecode has `return` at the end of each block, recompiled uses `goto` to a shared `return` at method end.

Example:
```
Original:  ... ; iconst_1; putstatic X; return    (inline return)
Recompiled: ... ; iconst_1; putstatic X; goto 187 (goto shared return)
```

**Why**: javac makes different decisions about whether to duplicate return instructions or use a goto to a shared exit. This depends on the exact structure of the decompiled source. The decompiler may merge what were originally separate exit paths into a single return point.

**Fix approach**: In Vineflower's RTF mode, preserve separate return statements for each exit path rather than merging them. This may require tracking which original bytecode blocks had inline returns. The `removeRedundantReturns` pass in Vineflower strips these - suppressing this pass in RTF mode could help.

**Impact**: 25 methods (3% of non-EXACT)

### 5. DUP Pattern Differences (13 methods) - MEDIUM

Pattern: `FIRST_DIFF(astore_1->dup)` - all in `test_IsQuadranglesAreTransposed` methods.

**What**: The original bytecode uses DUP to keep a value on the stack while also storing it, but the recompiled version uses a separate load instead.

**Why**: Vineflower decompiles DUP patterns into separate variable assignments. When recompiled, javac may not reconstruct the DUP pattern.

**Fix approach**: Vineflower's DUP handling in RTF mode. Some DUP patterns are already handled - this may need extension for the specific pattern used in test methods.

**Impact**: 13 methods (2% of non-EXACT)

### 6. Variable Slot/Copy Propagation (10+ methods) - MEDIUM

Example: PZMath.clamp methods (float, int, long variants)

**What**: Original bytecode uses a copy variable (`fload_3` / `iload_3`) in a comparison, but recompiled uses the original parameter (`fload_0` / `iload_0`).

```
Original:  fload_0; fstore_3; fload_3; fload_1; fcmpg  (uses copy)
Recompiled: fload_0; fstore_3; fload_0; fload_1; fcmpg  (uses original)
```

**Why**: Source has `float0 = float1; if (float1 < float2)` but original bytecode did `float0 = float1; if (float0 < float2)`. javac performs copy propagation, replacing `float0` with `float1` since `float0` was just assigned from `float1`. The decompiler correctly decompiles using the propagated form, but the original bytecode used the pre-propagation form.

**Fix approach**: In Vineflower RTF mode, suppress copy propagation - keep the assigned variable usage instead of substituting the source. This affects the variable resolution / copy propagation pass.

**Impact**: 10+ methods (1% of non-EXACT)

### 7. Specific fromLua Pattern (7 methods) - SMALL

Pattern: `FIRST_DIFF(aload_1->iconst_m1)` in `fromLua0`, `fromLua1` methods.

**What**: Method dispatchers with switch statements where the decompiler produces a different default case pattern.

**Fix approach**: Needs specific investigation of the fromLua method decompilation.

**Impact**: 7 methods (<1% of non-EXACT)

### 8. FUZZY_COMPUTATION (18 methods) - HARD

These have computation differences (different opcodes, not just reordering). Two main sub-patterns:
- OpenSimplexNoise.eval (2 methods, 32-44 insn diff): Variable slot assignment differs for large math computations
- Various game methods (16 methods, 2-12 insn diff): Mix of patterns including branch inversions, try-catch differences, and expression evaluation order

**Fix approach**: Each needs individual investigation. Many may be unfixable (javac internal optimization differences).

### 9. CORE_OPS_ONLY (2 methods) - INVESTIGATE

Only 2 methods at this lowest tier:
- `PlayerDBHelper.isPlayerAlive` (+11 insns difference)
- `LuaManager$GlobalObject.deleteAllGameModeSaves` (+4 insns difference)

These likely have structural decompilation errors that need individual investigation.

## Priority Ranking

| Priority | Pattern | Methods | Effort | Fix Location |
|----------|---------|---------|--------|-------------|
| 1 | Branch polarity (bare bool) | 40+ | Low | Vineflower IfStatement.toJava |
| 2 | GOTO vs Return | 25 | Medium | Vineflower removeRedundantReturns |
| 3 | Field owner class | 16 | Medium | Vineflower FieldExprent |
| 4 | Copy propagation | 10+ | Medium | Vineflower variable resolution |
| 5 | DUP patterns | 13 | Medium | Vineflower SimplifyExprentsHelper |
| 6 | Block layout | 196 | High | Vineflower FlattenStatementsHelper |
| 7 | FUZZY_COMPUTATION | 18 | High | Individual investigation |
| 8 | fromLua dispatch | 7 | Low | Vineflower switch handling |

## Files Modified This Session

### Vineflower: `IfStatement.java`
- Extended `rtfGetEffectiveComparisonType()` to handle bare VarExprent/FieldExprent
- Extended `rtfBuildCorrectedCondition()` to wrap/unwrap BOOL_NOT for bare booleans
- Status: Implemented but not verified - the fix doesn't appear to trigger. Needs debugging of why `originalBytecodeType` or the condition type don't match expectations at render time. Possible that simplification passes are changing the condition type before render, or that the code path isn't reached for these specific methods.

## Top Affected Classes

| Class | Non-EXACT Methods |
|-------|-------------------|
| IsoGridSquare | 21 |
| GameServer | 17 |
| IsoCell | 13 |
| BaseVehicle | 13 |
| IsoGameCharacter | 12 |
| test_IsQuadranglesAreTransposed | 13 |
| LosUtil | 5 |
| AnimatedModel | 4 |
| OpenSimplexNoise | 2 |
