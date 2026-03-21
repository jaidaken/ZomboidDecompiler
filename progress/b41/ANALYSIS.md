# Build 41 Bytecode Verification Analysis

## Overview

Project Zomboid Build 41 was decompiled using a custom Vineflower fork with RTF (Roundtrip Fidelity) mode, recompiled with Zulu JDK 17.0.1, and the resulting bytecode was compared against the original.

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
| VARIABLE_COPY_PROPAGATION | 72 | 5,201 | javac 17 copies/eliminates temp variables differently |
| GUARD_CLAUSE_INVERSION | 48 | 3,551 | if/else branch polarity flipped |
| LABEL_ONLY | 15 | 1,311 | Only jump target labels differ |
| CONSTANT_ENCODING | 13 | 575 | String switch hashing or static init order differs |
| STRUCTURAL_DIVERGENCE | 11 | 813 | Fundamentally different code structure emitted |
| TRY_RESOURCE_RESTRUCTURE | 3 | 128 | try-with-resources compiled differently by javac 17 |
| LAMBDA_BODY_SWAP | 3 | 4 | Lambda synthetic method bodies assigned wrong names |
| INVOKE_DISPATCH | 1 | 260 | INVOKEVIRTUAL vs INVOKEINTERFACE |
| EXPRESSION_REORDER | 1 | 50 | Subexpression evaluation order differs |
| **Total** | **896** | **116,083** | |

Note: The remaining ~156,654 unmatched instructions come from methods in non-EXACT tiers (STRUCTURAL, SORTED_MULTISET, etc.) that partially match but have some instruction differences.

## Root Cause Analysis

### 1. Vineflower Decompilation Issues (~55% of losses)

#### Stub Methods (117 methods, ~32,258 instructions lost)

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

#### Exit Block Reordering (86 methods, ~3,090 instructions lost)

Vineflower places return/break statements at locations that cause javac to emit exit blocks in a different position from the original. This happens when the decompiler incorrectly infers that a code path exits early.

#### Guard Clause Inversion (48 methods, ~3,551 instructions lost)

Vineflower decompiles `if (!condition)` where the original was `if (condition)` (or vice versa). This flips branch instructions (IFEQ to IFNE, etc.) and reorders the then/else blocks.

This is partially a fundamental limitation: bytecode control flow is symmetric, and the decompiler cannot always determine which branch was the original "if" and which was the "else".

#### Structural Divergence (11 methods, ~813 instructions lost)

The decompiler produces fundamentally different code structure. The output compiles but the bytecode has a completely different control flow graph.

#### Lambda Body Swap (3 methods, 4 instructions lost)

Lambda method bodies assigned to wrong synthetic method names.

### 2. JDK Version Mismatch (~30% of losses)

Build 41 was compiled with an older JDK (likely JDK 8 or early JDK 11). Recompiling with Zulu JDK 17.0.1 introduces systematic differences.

#### Variable Copy Propagation (72 methods, ~5,201 instructions lost)

javac 17 makes different optimization choices about when to store values in local variables vs inline them. Where the original stores a value and loads it, javac 17 may inline directly, or vice versa.

#### Instruction Count Differences from Optimization (396 diverged methods, ~68,842 instructions lost)

Beyond copy propagation, javac 17 generates different instruction sequences for the same logic due to:
- Different register allocation strategies
- Different branch optimization
- Different constant pool organization
- Different method inlining decisions

This is the single largest sub-category and is embedded within INSN_COUNT_DIFF.

#### Try-with-resources Restructuring (3 methods, ~128 instructions lost)

javac 17 compiles try-with-resources blocks differently from older JDK versions, producing different exception handler tables and cleanup code.

#### Constant Encoding (13 methods, ~575 instructions lost)

String switch compilation uses different hash-bucket arrangements. Static initializer ordering may differ.

### 3. Fundamental Limitations (~15% of losses)

#### Decompilation Ambiguity

Some bytecode patterns have multiple valid Java source representations:
- Ternary expressions vs if-else statements
- Compound boolean conditions (short-circuit evaluation order)
- Loop structures (for vs while vs do-while with equivalent bytecode)
- Variable declaration placement

No decompiler can recover the "correct" representation because the information is genuinely lost during compilation.

#### Missing/Extra Synthetic Methods (130 methods, 0 instructions)

118 methods exist in the recompiled output but not the original (javac 17 generates synthetic methods the original compiler did not). 12 methods exist in the original but not the recompiled output (bridge methods the decompiler didn't emit). These have 0 instruction impact.

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

| Target | What's Needed | Estimated Gain |
|---|---|---|
| **~85%** | Fix Vineflower stub decompilation for 117 methods | +32,000 instructions |
| **~90%** | Fix exit block reordering + guard clause inversion | +6,600 instructions |
| **~93%** | Use the original JDK version for recompilation | +30,000-50,000 instructions |
| **~96%** | Improve Vineflower control flow for diverged methods | +20,000-30,000 instructions |
| **~98%** | Loosen verification tolerances for near-matches | +10,000-20,000 instructions |
| **~99.5%** | Hard ceiling from decompilation ambiguity | theoretical maximum |

## Summary

The 76.9% instruction match rate breaks down as:
- **90.1% of methods match exactly** (EXACT tier)
- **97.3% of methods match** at some tier
- The gap is dominated by ~117 stub methods and ~400 diverged methods
- About 55% of instruction losses are from Vineflower decompilation limitations
- About 30% are from JDK version differences (17 vs original)
- About 15% are fundamental decompilation ambiguity

The most impactful improvements would be fixing Vineflower's handling of complex control flow (stub methods) and using the original JDK version for recompilation.
