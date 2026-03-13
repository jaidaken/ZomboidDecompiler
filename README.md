# Zomboid Decompiler
Simplified decompilation tool for Project Zomboid powered by [Vineflower](https://github.com/Vineflower/vineflower).
## Usage
### Windows
1) Install [Java 17](https://www.oracle.com/fr/java/technologies/downloads/) or above.
2) Download the latest .zip from [Releases](https://github.com/demiurgeQuantified/ZomboidDecompiler/releases/latest).
3) Extract the zip.
4) Navigate to `bin/` and run `ZomboidDecompiler.bat`.
5) Wait a few minutes for decompilation to complete. The black box will close when the program has finished.  
   - If you receive an error about not being able to find the game directory, open your command line to the `bin` folder and execute ``ZomboidDecompiler.bat "PATH"``, replacing `PATH` with the path to your game installation's `ProjectZomboid` folder.
     - Example: ``ZomboidDecompiler.bat "D:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"``

The decompiled source code will be written to `output/`, along with the dependencies and game jar.

### Other
1) Install [Java 17](https://www.oracle.com/fr/java/technologies/downloads/) or above.
2) Download the latest .zip from [Releases](https://github.com/demiurgeQuantified/ZomboidDecompiler/releases/latest).
3) Extract the zip.
4) Open your command line to the `bin` folder and execute ``ZomboidDecompiler "PATH"``, replacing `PATH` with the path to your game installation's `ProjectZomboid` folder.
   - Example: ``ZomboidDecompiler "D:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"``
5) Wait a few minutes for decompilation to complete.
The decompiled source code will be written to `output/`, along with the dependencies and game jar.

## Features
- Single click game decompilation.
- Automatic gathering of game dependencies as decompilation context and for future recompilation.
- Renaming of function parameters using Rosetta data.
- Renaming of other variables according to type to enhance readability.
- Line number remapping for remote debugging.
- **Post-decompilation source transforms** — automatically applies 25+ fixes to Vineflower output to correct type inference errors, raw generic issues, cast disambiguation, instanceof scope escapes, switch expression problems, and more. The transforms are applied during decompilation so the output is ready to recompile.
- **Bytecode verification** — compare original bytecode against recompiled classes at the instruction level to validate decompilation accuracy. Supports semantic normalization, guard clause detection, label isomorphism, and block-level matching.

## Bytecode Verification
The `verify` command compares original bytecode against recompiled classes to detect decompilation errors.

```bash
# Compare original JAR against recompiled classes directory
ZomboidDecompiler verify path/to/projectzomboid.jar path/to/recompiled-classes/

# With options
ZomboidDecompiler verify original.jar recompiled/ --semantic --verbose --context 10
```

**Options:**
| Option | Description |
|--------|-------------|
| `--class-pattern` | Class name pattern filter (default: `zombie.*`) |
| `--verbose` | Show all methods including matches |
| `--strict-vars` | Compare variable indices directly without normalization |
| `--context N` | Instructions to show around diffs (default: 5) |
| `--no-color` | Disable ANSI color output |
| `--summary-only` | Only show summary statistics |
| `--semantic` | Enable semantic normalization (DUP/store-load-return/GOTO) |

The `verifyBytecode` Gradle task is also available: `gradlew verifyBytecode`. Exit code 2 indicates mismatches were found.

### Standalone Transforms
The `TransformFiles` utility can apply post-decompilation transforms to an existing directory of decompiled source files without re-running the decompiler.

## Version compatibility chart
Sometimes the game changes too much for Zomboid Decompiler to reasonably maintain compatibility with older versions.
Downloads for the latest version supporting certain game versions are listed here.

| Game version    | Last supporting version                                                               |
|-----------------|---------------------------------------------------------------------------------------|
| 42.13.0–latest  | [latest](https://github.com/demiurgeQuantified/ZomboidDecompiler/releases/latest)     |
| unknown–42.12.3 | [v0.2.3](https://github.com/demiurgeQuantified/ZomboidDecompiler/releases/tag/v0.2.3) |

This fork has been used specifically for decompiling **Build 41**. The post-decompilation transforms and bytecode verification were developed against Build 41 bytecode.

## Command Line Interface
Launch with ``-h`` or ``--help`` for information about command line parameters.

## Remote debugging
A basic guide on using ZomboidDecompiler for remote debugging is hosted [here](https://github.com/demiurgeQuantified/PZModdingGuides/blob/main/guides/RemoteDebugging.md).

## Building
ZomboidDecompiler can be built with `gradlew build`.  
You can include Rosetta files in `src/main/resources/rosetta/` to be used as defaults when no rosetta directory is passed.
The standard binaries in Releases are built with the
[latest Rosetta data](https://github.com/PZ-Umbrella/pz-rosetta-source) included.
