# Build 41 Bytecode Verification Analysis

## Overview

Project Zomboid Build 41 was compiled with Zulu JDK 17.0.1. It was decompiled using a custom Vineflower fork with RTF (Roundtrip Fidelity) mode, then recompiled with the same Zulu JDK 17.0.1, and the resulting bytecode compared against the original.

Since the same compiler is used for both original and recompilation, all bytecode differences stem from the decompilation process - Vineflower produces Java source that is semantically equivalent but structurally different from the original, causing javac to emit different bytecode.

| Metric | Value |
|---|---|
| Decompiled source files | 1,599 |
| Compilation errors | **0** |
| Total classes verified | 2,978 |
| Total methods verified | 33,086 |
| Methods matched | 32,190 (97.3%) |
| Total instructions | 1,180,433 |
| Instructions matched | 907,696 (76.9%) |
| Instructions unmatched | 272,737 (23.1%) |

The instruction match rate (76.9%) is lower than the method match rate (97.3%) because the 896 unmatched methods are disproportionately large, averaging ~130 instructions each vs ~28 for matched methods.

## Match Tiers

The verifier classifies each method into one of six tiers, from best to worst:

| Tier | Methods | % | Description |
|---|---|---|---|
| EXACT | 29,803 | 90.1% | Bytecode identical instruction-for-instruction |
| STRUCTURAL | 1,014 | 3.1% | Same structure, minor label/jump target differences |
| STRUCTURAL_NO_TRYCATCH | 3 | <0.1% | Structural match ignoring try-catch table differences |
| SORTED_MULTISET | 1,019 | 3.1% | Same instructions present but in different order |
| FUZZY_COMPUTATION | 272 | 0.8% | Same operations with minor computation differences |
| CORE_OPS_ONLY | 79 | 0.2% | Core operations match, peripheral differences |
| **NONE** | **896** | **2.7%** | **No match at any tier** |

Methods in tiers EXACT through CORE_OPS_ONLY are all counted as "matched" (32,190). Only the 896 NONE-tier methods are unmatched.

## Mismatch Categories

The 896 NONE-tier methods break down by root cause:

| Category | Methods | Instructions Lost | Description |
|---|---|---|---|
| INSN_COUNT_DIFF | 513 | 101,100 | Recompiled instruction count differs from original |
| UNCATEGORIZED | 130 | 0 | Missing methods (synthetic/bridge) |
| EXIT_BLOCK_REORDER | 86 | 3,090 | Return/exit blocks placed in different order |
| VARIABLE_COPY_PROPAGATION | 72 | 5,201 | Vineflower restructures variable usage differently |
| GUARD_CLAUSE_INVERSION | 48 | 3,551 | if/else branch polarity flipped |
| LABEL_ONLY | 15 | 1,311 | Only jump target labels differ |
| CONSTANT_ENCODING | 13 | 575 | String switch hashing or static init order differs |
| STRUCTURAL_DIVERGENCE | 11 | 813 | Fundamentally different code structure emitted |
| TRY_RESOURCE_RESTRUCTURE | 3 | 128 | try-with-resources decompiled with different structure |
| LAMBDA_BODY_SWAP | 3 | 4 | Lambda synthetic method bodies assigned wrong names |
| INVOKE_DISPATCH | 1 | 260 | INVOKEVIRTUAL vs INVOKEINTERFACE difference |
| EXPRESSION_REORDER | 1 | 50 | Subexpression evaluation order differs |
| **Total** | **896** | **116,083** | |

Note: The remaining ~156,654 unmatched instructions come from methods in non-EXACT tiers (STRUCTURAL, SORTED_MULTISET, etc.) that partially match but have some instruction differences.

## Root Cause Analysis

Since the same JDK (Zulu 17.0.1) is used for both original compilation and recompilation, **all differences are caused by the decompilation process**. Vineflower produces Java source code that compiles to different bytecode than the original because the decompiler cannot perfectly recover the original source structure.

### 1. Stub Methods - Vineflower Decompilation Failures (117 methods, ~32,258 instructions lost)

Vineflower fails to reconstruct complex control flow and emits `return;` or `return null;` stubs. These are methods where the recompiled output has 1-2 instructions but the original has hundreds or thousands.

Top 10 stub methods by lost instructions:

| Method | Original | Recompiled | Lost |
|---|---|---|---|
| Item.DoParam | 4,205 | 1 | 4,204 |
| CircleLineIntersect.checkforcecirclescollidetime | 2,386 | 2 | 2,384 |
| Core.loadOptions | 1,832 | 2 | 1,830 |
| Item.InstanceItem | 1,653 | 2 | 1,651 |
| CellLoader.DoTileObjectCreation | 970 | 1 | 969 |
| WeatherPeriod.createWeatherPattern | 970 | 1 | 969 |
| ModelLoader.loadTxt | 951 | 2 | 949 |
| IsoGridSquare.BurnWalls | 908 | 1 | 907 |
| IsoChunkMap.renderBloodForChunks | 620 | 1 | 619 |
| SurvivorDesc.createSurvivorVariables | 504 | 1 | 503 |

These typically have deeply nested try-catch blocks, large switch statements, or complex loop patterns that Vineflower's control flow reconstruction cannot handle.

**Fixable**: Yes, in Vineflower. Each stub method needs individual investigation of why the decompiler fails.

### 2. Diverged Methods - Structural Reconstruction Differences (396 methods, ~68,842 instructions lost)

The largest category. Vineflower successfully decompiles these methods (they compile), but the reconstructed Java source differs structurally from the original. When javac recompiles the decompiled source, it produces different bytecode because:

- **Variable usage patterns differ**: Vineflower introduces temporary variables or eliminates them in different places than the original code
- **Control flow reconstruction differs**: Loop structures, if-else chains, and switch statements are reconstructed with different structure
- **Expression ordering differs**: Subexpressions are evaluated in a different order

Since the same compiler is used, any bytecode difference means the decompiled Java source is structurally different from the original. The compiler is deterministic - same source always produces same bytecode.

**Fixable**: Partially. Some are fixable by improving Vineflower's RTF reconstruction heuristics. Others represent genuine ambiguity where multiple valid Java sources exist for the same bytecode.

### 3. Exit Block Reordering (86 methods, ~3,090 instructions lost)

Vineflower places return/break statements at locations that cause javac to emit exit blocks in a different position from the original. This happens when the decompiler incorrectly infers where returns should go in if-else chains with multiple exit points.

**Fixable**: Yes, in Vineflower. Improve control flow reconstruction for methods with multiple return paths.

### 4. Variable Copy Propagation (72 methods, ~5,201 instructions lost)

Vineflower restructures variable usage - introducing or eliminating temporary variable copies compared to the original source. Since the same javac is used, this means the decompiled source uses variables differently than the original.

**Fixable**: Yes, in Vineflower. The RTF mode should preserve the original variable patterns from the bytecode.

### 5. Guard Clause Inversion (48 methods, ~3,551 instructions lost)

Vineflower decompiles `if (!condition)` where the original was `if (condition)` (or vice versa). This flips branch instructions (IFEQ to IFNE, etc.) and reorders the then/else blocks.

This is partially a fundamental limitation: bytecode control flow is symmetric. The decompiler uses heuristics to guess which branch was the "if" and which was the "else", and sometimes guesses wrong.

**Fixable**: Partially. Heuristics can be improved (e.g., shorter block is usually the guard), but some cases are genuinely ambiguous.

### 6. Other Categories (47 methods, ~3,023 instructions lost)

- **LABEL_ONLY** (15 methods, 1,311 lost): Jump target labels differ. Could potentially be treated as matches.
- **CONSTANT_ENCODING** (13 methods, 575 lost): String switch hash ordering or static field init ordering differs.
- **STRUCTURAL_DIVERGENCE** (11 methods, 813 lost): Fundamentally different code structure.
- **TRY_RESOURCE_RESTRUCTURE** (3 methods, 128 lost): try-with-resources decompiled differently.
- **LAMBDA_BODY_SWAP** (3 methods, 4 lost): Lambda bodies assigned to wrong synthetic method names.
- **INVOKE_DISPATCH** (1 method, 260 lost): Method dispatch instruction type differs.
- **EXPRESSION_REORDER** (1 method, 50 lost): Subexpression evaluation order differs.

### 7. Missing/Extra Methods (130 methods, 0 instructions)

- 118 methods exist in the recompiled output but not the original (javac generates synthetic methods for patterns Vineflower decompiles differently)
- 12 methods exist in the original but not the recompiled output (bridge methods Vineflower didn't emit)
- These have 0 instruction impact.

## Top 20 Classes by Lost Instructions

| Class | Match % | Lost | Matched/Total Methods |
|---|---|---|---|
| zombie/scripting/objects/Item | 27.0% | 5,855 | 244/246 |
| zombie/iso/IsoGridSquare | 61.7% | 5,402 | 357/388 |
| zombie/characters/IsoGameCharacter | 65.0% | 4,361 | 910/928 |
| zombie/characters/IsoPlayer | 58.5% | 4,320 | 391/410 |
| zombie/creative/.../OpenSimplexNoise | 18.9% | 3,534 | 10/12 |
| zombie/network/GameServer | 68.3% | 3,345 | 293/310 |
| zombie/vehicles/BaseVehicle | 74.8% | 3,150 | 481/500 |
| zombie/iso/IsoCell | 57.2% | 2,884 | 196/212 |
| zombie/characters/IsoZombie | 60.2% | 2,510 | 215/226 |
| zombie/vehicles/CircleLineIntersect | 2.3% | 2,505 | 3/5 |
| zombie/iso/IsoChunk | 68.1% | 2,457 | 88/101 |
| zombie/vehicles/PolygonalMap2 | 39.4% | 2,430 | 46/53 |
| zombie/iso/IsoWorld | 28.7% | 2,406 | 98/106 |
| zombie/characters/.../BodyDamage | 49.1% | 2,147 | 220/228 |
| zombie/core/Core | 68.9% | 2,040 | 391/395 |
| zombie/inventory/InventoryItem | 59.8% | 1,527 | 381/385 |
| zombie/iso/weather/WeatherPeriod | 46.6% | 1,521 | 63/70 |
| zombie/ui/UIManager | 43.6% | 1,382 | 110/114 |
| zombie/VirtualZombieManager | 45.0% | 1,380 | 31/39 |
| zombie/iso/IsoObject | 72.4% | 1,291 | 295/303 |

## Path to Higher Match Rates

All improvements require Vineflower decompiler fixes since the same JDK is used for both original and recompilation.

| Target | What's Needed | Estimated Gain |
|---|---|---|
| **~80%** | Fix top 20 stub methods (largest individual losses) | +20,000 instructions |
| **~85%** | Fix all 117 stub methods | +32,000 instructions |
| **~88%** | Fix exit block reordering + guard clause inversion | +6,600 instructions |
| **~92%** | Improve control flow for top 100 diverged methods | +30,000 instructions |
| **~95%** | Fix variable copy propagation + remaining diverged methods | +40,000 instructions |
| **~97%** | Fix STRUCTURAL/SORTED_MULTISET tier methods to EXACT | +20,000 instructions |
| **~99%** | Fix remaining edge cases | +10,000 instructions |
| **~99.5%** | Hard ceiling from genuine decompilation ambiguity | theoretical maximum |

## Summary

The 76.9% instruction match rate breaks down as:
- **90.1% of methods match exactly** (EXACT tier)
- **97.3% of methods match** at some tier
- **All losses are from Vineflower decompilation** since the same Zulu JDK 17.0.1 is used for both original compilation and recompilation
- The gap is dominated by 117 stub methods (32K instructions) and 396 diverged methods (69K instructions)
- About 85% of losses are fixable in Vineflower with improved control flow reconstruction
- About 15% represent genuine decompilation ambiguity (hard ceiling ~99.5%)

The most impactful improvement is fixing Vineflower's handling of complex control flow (stub methods), followed by improving structural reconstruction for diverged methods.
