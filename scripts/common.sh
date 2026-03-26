#!/usr/bin/env bash
# Shared setup for build scripts.
# Source this file, don't execute it directly.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILDS_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
VENV_PYTHON="$PROJECT_DIR/.venv/bin/python3"
GENERATE_SCRIPT="$SCRIPT_DIR/generate_progress_image.py"

# Zulu JDK paths — scripts override JAVA_BIN / JAVAC_BIN for their target version
ZULU17_HOME="$BUILDS_DIR/tools/zulu-jdk-17.0.1"
ZULU25_HOME="$BUILDS_DIR/tools/zulu-jdk-25.0.1"

# Test dependency JARs (JUnit, Hamcrest) — game ships test classes that need these
TOOLS_DIR="$BUILDS_DIR/tools"
JUNIT_JAR="$TOOLS_DIR/junit-4.13.2.jar"
HAMCREST_JAR="$TOOLS_DIR/hamcrest-core-1.3.jar"

# Default to Zulu 17 (build 41 / decompiler toolchain)
# JAVA_HOME is set so Gradle builds also use the correct JDK
JAVA_BIN="java"
JAVAC_BIN="javac"
if [ -x "$ZULU17_HOME/bin/java" ]; then
    export JAVA_HOME="$ZULU17_HOME"
    export PATH="$ZULU17_HOME/bin:$PATH"
    JAVA_BIN="$ZULU17_HOME/bin/java"
    JAVAC_BIN="$ZULU17_HOME/bin/javac"
fi

# Ensure the project is built and up to date.
# Rebuilds vineflower if its source changed, then rebuilds ZomboidDecompiler
# with clean installDist to avoid stale cached jars.
INSTALL_DIR="$PROJECT_DIR/build/install/ZomboidDecompiler"
VINEFLOWER_DIR="$BUILDS_DIR/vineflower"
VINEFLOWER_JAR="$VINEFLOWER_DIR/build/libs/vineflower-1.11.2+local.jar"
VINEFLOWER_COPY="$VINEFLOWER_DIR/build/libs/vineflower-1.11.2.jar"
ensure_built() {
    local vineflower_changed=false

    # Rebuild vineflower if any source file is newer than the jar
    if [ -d "$VINEFLOWER_DIR/src" ]; then
        if [ ! -f "$VINEFLOWER_JAR" ] || \
           [ -n "$(command find "$VINEFLOWER_DIR/src" -name "*.java" -newer "$VINEFLOWER_JAR" 2>/dev/null | head -1)" ]; then
            echo "Building Vineflower..."
            JAVA_HOME="$ZULU17_HOME" "$VINEFLOWER_DIR/gradlew" -p "$VINEFLOWER_DIR" clean allJar --quiet 2>/dev/null
            cp "$VINEFLOWER_JAR" "$VINEFLOWER_COPY"
            vineflower_changed=true
        fi
    fi

    # Also check if vineflower jar is newer than installed jar (manual builds)
    # Check both the copy and the +local jar
    local installed_jar="$INSTALL_DIR/lib/vineflower-1.11.2-module.jar"
    if [ -f "$installed_jar" ]; then
        if { [ -f "$VINEFLOWER_COPY" ] && [ "$VINEFLOWER_COPY" -nt "$installed_jar" ]; } || \
           { [ -f "$VINEFLOWER_JAR" ] && [ "$VINEFLOWER_JAR" -nt "$installed_jar" ]; }; then
            # Ensure the copy exists
            [ -f "$VINEFLOWER_JAR" ] && cp "$VINEFLOWER_JAR" "$VINEFLOWER_COPY"
            vineflower_changed=true
        fi
    fi

    if [ ! -d "$INSTALL_DIR/lib" ]; then
        echo "Building ZomboidDecompiler..."
        JAVA_HOME="$ZULU17_HOME" "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" installDist --quiet
    elif $vineflower_changed; then
        echo "Rebuilding ZomboidDecompiler (vineflower changed)..."
        JAVA_HOME="$ZULU17_HOME" "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" clean installDist --quiet
    fi
}

# Build module path from installed libs
get_module_path() {
    local mp
    mp="$(printf '%s:' "$INSTALL_DIR"/lib/*.jar)"
    echo "${mp%:}"
}

# Ensure Python venv with matplotlib + squarify
ensure_venv() {
    if [ ! -f "$VENV_PYTHON" ]; then
        echo "Creating Python venv..."
        python3 -m venv "$PROJECT_DIR/.venv"
        "$VENV_PYTHON" -m pip install --quiet matplotlib squarify
    fi
}

# ── Progress display helpers ──────────────────────────────────────────

# Spinner with elapsed time — runs until the given PID exits.
# Usage: spinner <pid> <message>
spinner() {
    local pid="$1"
    local msg="$2"
    local chars='|/-\'
    local start=$SECONDS
    local i=0

    # Hide cursor
    tput civis 2>/dev/null || true

    while kill -0 "$pid" 2>/dev/null; do
        local elapsed=$(( SECONDS - start ))
        local mins=$(( elapsed / 60 ))
        local secs=$(( elapsed % 60 ))
        printf "\r  %s %s [%02d:%02d]" "${chars:i%4:1}" "$msg" "$mins" "$secs"
        i=$(( i + 1 ))
        sleep 0.15
    done

    local elapsed=$(( SECONDS - start ))
    local mins=$(( elapsed / 60 ))
    local secs=$(( elapsed % 60 ))
    printf "\r  done  %s [%02d:%02d]%*s\n" "$msg" "$mins" "$secs" 10 ""

    # Show cursor
    tput cnorm 2>/dev/null || true
}

# Progress bar that monitors file count in a directory.
# Usage: progress_bar <pid> <message> <total> <watch_dir> <extension>
progress_bar() {
    local pid="$1"
    local msg="$2"
    local total="$3"
    local watch_dir="$4"
    local ext="${5:-java}"
    local start=$SECONDS
    local bar_width=30

    # Hide cursor
    tput civis 2>/dev/null || true

    while kill -0 "$pid" 2>/dev/null; do
        local current=0
        if [ -d "$watch_dir" ]; then
            current=$(command find "$watch_dir" -name "*.$ext" 2>/dev/null | wc -l)
        fi

        local elapsed=$(( SECONDS - start ))
        local mins=$(( elapsed / 60 ))
        local secs=$(( elapsed % 60 ))

        local pct=0
        if [ "$total" -gt 0 ]; then
            pct=$(( current * 100 / total ))
        fi
        if [ "$pct" -gt 100 ]; then
            pct=100
        fi

        local filled=$(( pct * bar_width / 100 ))
        local empty=$(( bar_width - filled ))

        local bar=""
        local j
        for (( j = 0; j < filled; j++ )); do bar+="#"; done
        for (( j = 0; j < empty; j++ ));  do bar+="."; done

        printf "\r  %s [%s] %d/%d (%d%%) [%02d:%02d]" \
            "$msg" "$bar" "$current" "$total" "$pct" "$mins" "$secs"
        sleep 1
    done

    # Final count
    local current=0
    if [ -d "$watch_dir" ]; then
        current=$(command find "$watch_dir" -name "*.$ext" 2>/dev/null | wc -l)
    fi
    local elapsed=$(( SECONDS - start ))
    local mins=$(( elapsed / 60 ))
    local secs=$(( elapsed % 60 ))

    local pct=0
    if [ "$total" -gt 0 ]; then
        pct=$(( current * 100 / total ))
    fi
    if [ "$pct" -gt 100 ]; then
        pct=100
    fi

    local filled=$(( pct * bar_width / 100 ))
    local empty=$(( bar_width - filled ))
    local bar=""
    local j
    for (( j = 0; j < filled; j++ )); do bar+="#"; done
    for (( j = 0; j < empty; j++ ));  do bar+="."; done

    printf "\r  %s [%s] %d/%d (%d%%) [%02d:%02d]%*s\n" \
        "$msg" "$bar" "$current" "$total" "$pct" "$mins" "$secs" 5 ""

    # Show cursor
    tput cnorm 2>/dev/null || true
}

# Count top-level .class files in a directory or JAR matching the zombie.* pattern.
# Excludes inner classes (containing $) so the count matches expected .java files.
# For directories, counts loose .class files plus classes inside any JARs.
# Usage: count_classes <path> [pattern_prefix]
count_classes() {
    local path="$1"
    local prefix="${2:-zombie/}"
    local count=0

    if [ -f "$path" ] && [[ "$path" == *.jar ]]; then
        count=$(jar tf "$path" 2>/dev/null | grep '\.class$' | grep "^$prefix" | grep -cv '\$' || true)
    elif [ -d "$path" ]; then
        # Count loose .class files under the prefix directory (exclude inner classes)
        if [ -d "$path/$prefix" ]; then
            count=$(command find "$path/$prefix" -name "*.class" ! -name '*$*' 2>/dev/null | wc -l)
        fi
        # Also count matching classes inside any JARs in the directory
        for jar in "$path"/*.jar; do
            [ -f "$jar" ] || continue
            local jar_count
            jar_count=$(jar tf "$jar" 2>/dev/null | grep '\.class$' | grep "^$prefix" | grep -cv '\$' || true)
            count=$(( count + jar_count ))
        done
    fi

    echo "$count"
}

# ── Core operations ───────────────────────────────────────────────────

run_decompile() {
    local input_path="$1"
    local output_path="$2"
    shift 2

    ensure_built
    local module_path
    module_path="$(get_module_path)"

    # Clean and recreate output
    rm -rf "$output_path"
    mkdir -p "$output_path"

    local total
    total="$(count_classes "$input_path")"
    echo "Decompiling $input_path -> $output_path ($total classes)"

    local log_file
    log_file=$(mktemp)

    "$JAVA_BIN" -Xmx16g \
        --module-path "$module_path" \
        --module com.github.zomboiddecompiler/com.github.zomboiddecompiler.commands.Decompile \
        "$input_path" "$output_path" "$@" > "$log_file" 2>&1 &
    local java_pid=$!

    if [ "$total" -gt 0 ]; then
        progress_bar "$java_pid" "Decompiling" "$total" "$output_path/source" "java"
    else
        spinner "$java_pid" "Decompiling"
    fi

    local rc=0
    wait "$java_pid" || rc=$?

    if [ "$rc" -ne 0 ]; then
        echo "  Decompilation failed (exit code $rc). Log: $log_file"
        return "$rc"
    fi
    rm -f "$log_file"
}

run_recompile() {
    local source_dir="$1"
    local output_dir="$2"
    local game_dir="$3"
    shift 3
    local extra_cp=("$@")

    # Build classpath from game JARs
    local cp=""
    for jar in "$game_dir"/*.jar; do
        [ -f "$jar" ] || continue
        cp="${cp:+$cp:}$jar"
    done

    # For loose-class builds, add the game dir itself so non-decompiled classes resolve
    if [ -d "$game_dir/zombie" ]; then
        cp="${cp:+$cp:}$game_dir"
    fi

    # Add any extra classpath entries (e.g. copied projectzomboid.jar in Decompiled-src)
    for entry in "${extra_cp[@]+${extra_cp[@]}}"; do
        [ -n "$entry" ] && cp="${cp:+$cp:}$entry"
    done

    # Add test dependency JARs if available (game ships test classes that need JUnit)
    [ -f "$JUNIT_JAR" ] && cp="${cp:+$cp:}$JUNIT_JAR"
    [ -f "$HAMCREST_JAR" ] && cp="${cp:+$cp:}$HAMCREST_JAR"

    # Collect source files
    local src_list
    src_list=$(mktemp)
    command find "$source_dir" -name "*.java" > "$src_list"
    local total
    total=$(wc -l < "$src_list")

    if [ "$total" -eq 0 ]; then
        echo "No .java files found in $source_dir"
        rm -f "$src_list"
        return 1
    fi

    # Clean and recreate output
    rm -rf "$output_dir"
    mkdir -p "$output_dir"

    echo "Recompiling $total source files -> $output_dir"

    local log_file="$output_dir/compile-errors.log"

    "$JAVAC_BIN" \
        -d "$output_dir" \
        -cp "$cp" \
        -source "${JAVAC_SOURCE_VERSION:-17}" -target "${JAVAC_TARGET_VERSION:-17}" \
        -proc:none \
        -nowarn \
        -implicit:class \
        -Xmaxerrs 99999 \
        -Xmaxwarns 0 \
        "@$src_list" > "$log_file" 2>&1 &
    local javac_pid=$!

    spinner "$javac_pid" "Compiling $total files"

    local rc=0
    wait "$javac_pid" || rc=$?

    local compiled
    compiled=$(command find "$output_dir" -name "*.class" 2>/dev/null | wc -l)

    # Multi-pass: when compilation fails, exclude root-cause files and retry
    # so cascade failures can resolve dependencies from the game JAR.
    if [ "$rc" -ne 0 ]; then
        local max_passes=3
        local pass=1
        local prev_compiled="$compiled"

        while [ "$pass" -le "$max_passes" ]; do
            # Find files with their OWN errors (not cascade "cannot find symbol")
            local root_cause
            root_cause=$(mktemp)
            grep "^.*\.java:[0-9]*: error:" "$log_file" 2>/dev/null \
                | grep -v "error: cannot find symbol" \
                | grep -v "error: package .* does not exist" \
                | grep -v "error: cannot access " \
                | sed 's/:[0-9]*: error:.*//' \
                | sort -u > "$root_cause" || true

            local exclude_count
            exclude_count=$(wc -l < "$root_cause")

            if [ "$exclude_count" -eq 0 ]; then
                rm -f "$root_cause"
                break
            fi

            # Build reduced source list (exclude root-cause files)
            local new_src
            new_src=$(mktemp)
            grep -Fxvf "$root_cause" "$src_list" > "$new_src" || true
            rm -f "$root_cause"

            local new_total
            new_total=$(wc -l < "$new_src")

            if [ "$new_total" -ge "$(wc -l < "$src_list")" ]; then
                rm -f "$new_src"
                break
            fi

            local excluded=$((total - new_total))
            echo "  Pass $((pass + 1)): excluded $excluded root-cause files, retrying $new_total..."
            cp "$new_src" "$src_list"
            rm -f "$new_src"

            local pass_log
            pass_log=$(mktemp)
            "$JAVAC_BIN" \
                -d "$output_dir" \
                -cp "$cp" \
                -source "${JAVAC_SOURCE_VERSION:-17}" -target "${JAVAC_TARGET_VERSION:-17}" \
                -proc:none \
                -nowarn \
                -implicit:class \
                -Xmaxerrs 99999 \
                -Xmaxwarns 0 \
                "@$src_list" > "$pass_log" 2>&1 &
            javac_pid=$!

            spinner "$javac_pid" "Compiling (pass $((pass + 1)))"

            rc=0
            wait "$javac_pid" || rc=$?

            # Append pass errors to main log (don't overwrite pass 1 errors)
            cat "$pass_log" >> "$log_file"
            rm -f "$pass_log"

            compiled=$(command find "$output_dir" -name "*.class" 2>/dev/null | wc -l)

            # Stop if no improvement or compilation is clean
            if [ "$compiled" -le "$prev_compiled" ] || [ "$rc" -eq 0 ]; then
                break
            fi
            prev_compiled="$compiled"
            pass=$((pass + 1))
        done
    fi

    # Fallback: compile remaining failures individually against the game JAR.
    # Each file resolves ALL deps from the JAR, eliminating cascade entirely.
    # Only files with genuine decompilation errors fail.
    if [ "$rc" -ne 0 ]; then
        local remaining
        remaining=$(mktemp)
        while IFS= read -r java_file; do
            local rel="${java_file#$source_dir/}"
            local class_file="$output_dir/${rel%.java}.class"
            [ -f "$class_file" ] || echo "$java_file"
        done < "$src_list" > "$remaining"

        local remain_count
        remain_count=$(wc -l < "$remaining")

        if [ "$remain_count" -gt 0 ]; then
            local jobs
            jobs=$(nproc 2>/dev/null || echo 4)
            echo "  Fallback: compiling $remain_count files individually ($jobs parallel)..."

            local fallback_log
            fallback_log=$(mktemp)

            # Small batches (20 files each) to limit cascade within a batch
            # while keeping JVM startup overhead reasonable.
            cat "$remaining" | xargs -P "$jobs" -L 20 \
                "$JAVAC_BIN" -d "$output_dir" -cp "$cp:$output_dir" \
                -source "${JAVAC_SOURCE_VERSION:-17}" -target "${JAVAC_TARGET_VERSION:-17}" \
                -proc:none -nowarn -implicit:none \
                -Xmaxerrs 99999 -Xmaxwarns 0 \
                2>"$fallback_log" || true

            local new_compiled
            new_compiled=$(command find "$output_dir" -name "*.class" 2>/dev/null | wc -l)
            local recovered=$((new_compiled - compiled))
            if [ "$recovered" -gt 0 ]; then
                echo "  Recovered $recovered additional classes ($new_compiled total)"
                compiled="$new_compiled"
            fi

            # Second fallback: compile truly individually (L-1) for files still missing
            # The L-20 batch can still cascade when 1 bad file kills 19 good ones.
            local still_missing
            still_missing=$(mktemp)
            while IFS= read -r java_file; do
                local rel="${java_file#$source_dir/}"
                local class_file="$output_dir/${rel%.java}.class"
                [ -f "$class_file" ] || echo "$java_file"
            done < "$remaining" > "$still_missing"
            local still_count
            still_count=$(wc -l < "$still_missing")
            if [ "$still_count" -gt 0 ]; then
                local fallback2_log
                fallback2_log=$(mktemp)
                cat "$still_missing" | xargs -P "$jobs" -L 1 \
                    "$JAVAC_BIN" -d "$output_dir" -cp "$cp:$output_dir" \
                    -source "${JAVAC_SOURCE_VERSION:-17}" -target "${JAVAC_TARGET_VERSION:-17}" \
                    -proc:none -nowarn -implicit:none \
                    -Xmaxerrs 99999 -Xmaxwarns 0 \
                    2>"$fallback2_log" || true
                new_compiled=$(command find "$output_dir" -name "*.class" 2>/dev/null | wc -l)
                local recovered2=$((new_compiled - compiled))
                if [ "$recovered2" -gt 0 ]; then
                    echo "  Recovered $recovered2 more classes via individual compilation ($new_compiled total)"
                    compiled="$new_compiled"
                fi
                if [ -s "$fallback2_log" ]; then
                    cat "$fallback2_log" >> "$log_file"
                fi
                rm -f "$fallback2_log"
            fi
            rm -f "$still_missing"

            # Merge fallback errors into main log
            if [ -s "$fallback_log" ]; then
                cat "$fallback_log" >> "$log_file"
            fi
            rm -f "$fallback_log"
        fi
        rm -f "$remaining"
    fi

    # Count errors from the log (covers all passes)
    local errors
    errors=$(grep -c "^.*\.java:[0-9]*: error:" "$log_file" 2>/dev/null || echo 0)
    local error_files
    error_files=$(grep "^.*\.java:[0-9]*: error:" "$log_file" 2>/dev/null \
        | sed 's/:[0-9]*: error:.*//' | sort -u | wc -l)
    local source_count
    source_count=$(wc -l < "$src_list")

    echo ""
    echo "  ========================================"
    echo "  Compilation Summary"
    echo "  ========================================"
    echo "  Source files:  $source_count"
    echo "  Classes built: $compiled"
    echo "  Errors:        $errors (in $error_files files)"
    if [ "$errors" -gt 0 ]; then
        echo "  Error log:     $log_file"
        echo ""
        echo "  Error breakdown:"
        grep "^.*\.java:[0-9]*: error:" "$log_file" 2>/dev/null \
            | sed 's/.*: error: //' | sort | uniq -c | sort -rn | head -10 \
            | while IFS= read -r line; do echo "    $line"; done
        echo "  ========================================"
    else
        echo "  ========================================"
        : > "$log_file"  # truncate to empty (no errors)
    fi

    rm -f "$src_list"
    # Partial compilation is expected — decompiled code often has errors.
    # Return success as long as some classes were produced.
    if [ "$compiled" -gt 0 ]; then
        return 0
    fi
    return "$rc"
}

run_verify() {
    local original="$1"
    local recompiled="$2"
    local report_path="$3"

    ensure_built
    local module_path
    module_path="$(get_module_path)"

    echo "Verifying bytecode..."
    local rc=0
    local log_file
    log_file=$(mktemp)

    "$JAVA_BIN" \
        --module-path "$module_path" \
        --module com.github.zomboiddecompiler/com.github.zomboiddecompiler.commands.Verify \
        "$original" "$recompiled" \
        --semantic --summary-only --json-report "$report_path" > "$log_file" 2>&1 &
    local java_pid=$!
    spinner "$java_pid" "Verifying"
    wait "$java_pid" || rc=$?
    if [ "$rc" -ne 0 ] && [ "$rc" -ne 2 ]; then
        echo "  Verification failed (exit code $rc). Log: $log_file"
        return "$rc"
    fi
    rm -f "$log_file"

    if [ -f "$report_path" ]; then
        ensure_venv
        "$VENV_PYTHON" -c "
import json
from collections import Counter
with open('$report_path') as f:
    data = json.load(f)
m = data['measures']
total = m['total_methods']
tiers = Counter()
for u in data['units']:
    for method in u.get('methods', []):
        tiers[method.get('matchTier', 'EXACT')] += 1
tiers['EXACT'] = tiers.get('EXACT', 0) + (total - sum(tiers.values()))

exact = tiers.get('EXACT', 0)
none = tiers.get('NONE', 0)

print()
print(f'  Byte-exact:  {exact:,} / {total:,} ({100*exact/total:.1f}%)')
if none > 0:
    print(f'  Unmatched:   {none:,}')
print()

non_exact = total - exact
if non_exact > 0:
    order = ['STRUCTURAL', 'SORTED_MULTISET', 'FUZZY_COMPUTATION', 'CORE_OPS_ONLY', 'NONE']
    for tier in order:
        c = tiers.get(tier, 0)
        if c > 0:
            print(f'    {c:>5,}  {tier}')
    print()
" 2>/dev/null || true

        local summary_path="${report_path%.json}_summary.json"
        "$VENV_PYTHON" -c "
import json
from collections import Counter
with open('$report_path') as f:
    data = json.load(f)
m = data['measures']
total = m['total_methods']
tiers = Counter()
for u in data['units']:
    for method in u.get('methods', []):
        tiers[method.get('matchTier', 'EXACT')] += 1
tiers['EXACT'] = tiers.get('EXACT', 0) + (total - sum(tiers.values()))
exact = tiers.get('EXACT', 0)
summary = {
    'exact': exact,
    'total': total,
    'tiers': {t: tiers.get(t, 0) for t in ['EXACT','STRUCTURAL','SORTED_MULTISET','FUZZY_COMPUTATION','CORE_OPS_ONLY','NONE']},
}
with open('$summary_path', 'w') as f:
    json.dump(summary, f, indent=2)
" 2>/dev/null || true
    fi
}

generate_image() {
    local report_path="$1"
    local output_path="$2"
    local title="$3"
    local source_dir="${4:-}"

    ensure_venv

    local args=("$report_path" "$output_path" "$title")
    if [ -n "$source_dir" ]; then
        args+=("$source_dir")
    fi

    "$VENV_PYTHON" "$GENERATE_SCRIPT" "${args[@]}" &
    local py_pid=$!

    spinner "$py_pid" "Generating progress image"

    wait "$py_pid"
    echo "  Image saved to $output_path"
}

# Launch Project Zomboid from a game directory.
# Usage: run_game <game_dir> [extra_classpath_prefix]
#
# When extra_classpath_prefix is given, those entries are prepended to the
# classpath so recompiled classes take priority over originals.
run_game() {
    local game_dir="$1"
    local extra_cp="${2:-}"

    local config="$game_dir/ProjectZomboid64.json"
    if [ ! -f "$config" ]; then
        echo "Launch config not found: $config"
        return 1
    fi

    local game_java="$game_dir/jre64/bin/java"
    if [ ! -x "$game_java" ]; then
        echo "Bundled JRE not found: $game_java"
        return 1
    fi

    # Parse classpath from JSON config
    local cp
    cp=$(python3 -c "
import json, sys
cfg = json.load(open('$config'))
print(':'.join(cfg.get('classpath', ['.'])))
")

    # Prepend extra classpath if provided (recompiled classes)
    if [ -n "$extra_cp" ]; then
        cp="$extra_cp:$cp"
    fi

    # Parse VM args from JSON config
    local vm_args
    vm_args=$(python3 -c "
import json, sys
cfg = json.load(open('$config'))
print(' '.join(cfg.get('vmArgs', [])))
")

    # Parse main class (convert slashes to dots)
    local main_class
    main_class=$(python3 -c "
import json, sys
cfg = json.load(open('$config'))
print(cfg['mainClass'].replace('/', '.'))
")

    # Disable Steam integration (not running from Steam)
    vm_args="$vm_args -Dzomboid.steam=0"

    echo "Game dir:    $game_dir"
    echo "JRE:         $game_java"
    echo "Main class:  $main_class"
    echo "Classpath:   $cp"
    echo ""

    cd "$game_dir"
    exec "$game_java" \
        -cp "$cp" \
        $vm_args \
        "$main_class" \
        "$@"
}
