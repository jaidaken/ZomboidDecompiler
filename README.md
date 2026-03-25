# Zomboid Decompiler

Bytecode-exact decompilation tool for Project Zomboid. Uses a [custom Vineflower fork](https://github.com/jaidaken/vineflower-zomboid) to decompile PZ, recompile with the same JDK, and verify the output matches the original bytecode instruction by instruction.

Forked from [demiurgeQuantified/ZomboidDecompiler](https://github.com/demiurgeQuantified/ZomboidDecompiler).

## Current status

### Build 41
![Build 41 Decompilation Progress](progress/b41/progress.png)

### Build 42
![Build 42 Decompilation Progress](progress/b42/progress.png)

## How it works

1. **Decompile** PZ bytecode using our Vineflower fork with RTF (Round-Trip Fidelity) mode
2. **Recompile** the decompiled source with the same JDK that compiled the original (Zulu 17.0.1)
3. **Verify** recompiled bytecode against the original, instruction by instruction

The point is to get decompiled source that recompiles to identical bytecode. Useful for PZ modding where you patch specific methods and need the rest to match the original.

## The Vineflower fork

The [custom Vineflower fork](https://github.com/jaidaken/vineflower-zomboid) adds RTF mode (`-rtf=1`) with these changes:

### RTF bytecode preservation

| Feature | Standard Vineflower | RTF Mode |
|---------|-------------------|----------|
| Branch direction | Negates for readability | Preserves original if/goto layout |
| Block ordering | javac default | Matches original bytecode offsets |
| Switch case order | Sorted for readability | Preserves bytecode order |
| Compound assignment | `x += y` | `x = x + y` (matches bytecode) |
| Prefix/postfix | Simplified `i++` | Preserves original form |
| Return ternary | `return cond ? a : b` | `if/else return` (matches bytecode) |
| Widening casts | Removed if implicit | Preserved (`(double)floatVal`) |
| LVT types | Inferred | Pinned from Local Variable Table |
| Pattern matching | `x instanceof Foo foo` | Explicit cast `(Foo)x` (avoids extra local vars) |
| Variable init | Always `= null`/`= 0` | Skipped when definitely assigned in all branches |

### RTF-specific fixes

- **Receiver-type casting** - emits `((InputStream)var).read()` when the bytecode receiver type differs from the variable's declared type
- **Orphaned anonymous class injection** - detects anonymous classes whose `.class` files exist but are never instantiated (dead code artifacts) and emits them to preserve `$N` numbering
- **Orphaned lambda emission** - emits synthetic `lambda$` methods that have no bootstrap reference (dead code from `if (false)` elimination)
- **Lambda ordering** - swaps if-else branches when lambda bytecode offsets would produce wrong `$N` numbering
- **Definite assignment analysis** - skips `= null`/`= 0`/`= 0.0` initializers when the variable is assigned in all branches before any read. Handles sequences, nested if-else, and chained assignments (`a = b = value`)
- **FinallyProcessor iinc fix** - includes `iinc` (opcode 132) in variable mapping during finally deinlining, fixing a bug where inlined finally copies with `iinc` on different variable slots couldn't be matched
- **Guard clause layout** - swaps if-else branches based on original bytecode offsets so javac's block layout matches the original
- **Dead code suppression**, **switch fall-through analysis**, **try-finally return analysis**, **orphaned label repair**, and various compilation bug fixes

## Differences from upstream

This fork adds 137 commits and ~13,000 lines beyond [demiurgeQuantified/ZomboidDecompiler](https://github.com/demiurgeQuantified/ZomboidDecompiler):

### Bytecode verification framework
A `verify` command that compares original bytecode against recompiled classes at the instruction level:
- Variable normalization (maps slot indices to encounter order)
- Multi-tier matching: EXACT, STRUCTURAL, SORTED_MULTISET, FUZZY_COMPUTATION, CORE_OPS_ONLY, NONE
- Semantic normalization (DUP patterns, store-load-return, GOTO inlining, guard clause detection)
- JSON report output and progress treemap image generation
- Mismatch categorization by root cause

### Full pipeline scripts
Automated decompile/recompile/verify pipelines for Build 41 and Build 42:
- Auto-rebuild when Vineflower source changes
- L-1 individual compilation fallback for cascade failure recovery
- Progress image generation (treemap visualization)

### PostDecompileTransforms (removed)
All post-decompile source transforms for B41 and B42 have been removed. Every issue they worked around was fixed at the source in the Vineflower fork.

## Setup

### Prerequisites
- The [Vineflower fork](https://github.com/jaidaken/vineflower-zomboid) cloned alongside this repo at `../vineflower/`
- Zulu JDK 17.0.1 (Build 41) and/or Zulu JDK 25.0.1 (Build 42) in `../tools/`
- PZ game files in `../build-41/vanilla-game-41/projectzomboid/` or `../build-42/vanilla-game-42/projectzomboid/`

### Directory layout
```
optizomb/
  vineflower/           # Vineflower fork
  ZomboidDecompiler/    # This repo
  tools/
    zulu-jdk-17.0.1/    # B41 JDK
    zulu-jdk-25.0.1/    # B42 JDK
  build-41/
    vanilla-game-41/projectzomboid/   # B41 game files
  build-42/
    vanilla-game-42/projectzomboid/   # B42 game files
```

## Usage

### Full pipeline
The scripts handle building Vineflower, rebuilding ZomboidDecompiler, decompiling, recompiling, verifying, and generating progress images:

```bash
# Build 41
./scripts/b41.sh

# Build 42
./scripts/b42.sh

# Individual steps
./scripts/b41.sh decompile
./scripts/b41.sh recompile
./scripts/b41.sh verify
```

### Verify only
```bash
./build/install/ZomboidDecompiler/bin/ZomboidDecompiler verify \
  path/to/original path/to/recompiled \
  --json-report report.json
```

## Supported builds
- **Build 41** - Zulu JDK 17.0.1
- **Build 42** - Zulu JDK 25.0.1

## Remote debugging
Guide: [PZModdingGuides/RemoteDebugging](https://github.com/demiurgeQuantified/PZModdingGuides/blob/main/guides/RemoteDebugging.md)
