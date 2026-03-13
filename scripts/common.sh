#!/usr/bin/env bash
# Shared setup for build scripts.
# Source this file, don't execute it directly.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILDS_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
VENV_PYTHON="$PROJECT_DIR/.venv/bin/python3"
GENERATE_SCRIPT="$SCRIPT_DIR/generate_progress_image.py"

# Use the Zulu JDK from tools if available, otherwise fall back to system java
JAVA_BIN="java"
ZULU_JAVA="$BUILDS_DIR/tools/zulu-jdk-17.0.1/bin/java"
if [ -x "$ZULU_JAVA" ]; then
    JAVA_BIN="$ZULU_JAVA"
fi

# Ensure the project is built
INSTALL_DIR="$PROJECT_DIR/build/install/ZomboidDecompiler"
ensure_built() {
    if [ ! -d "$INSTALL_DIR/lib" ]; then
        echo "Building ZomboidDecompiler..."
        "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" installDist --quiet
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

run_decompile() {
    local input_path="$1"
    local output_path="$2"
    shift 2

    ensure_built
    local module_path
    module_path="$(get_module_path)"

    echo "Decompiling $input_path -> $output_path ..."
    "$JAVA_BIN" \
        --module-path "$module_path" \
        --module com.github.zomboiddecompiler/com.github.zomboiddecompiler.commands.Decompile \
        "$input_path" "$output_path" "$@"
}

run_verify() {
    local original="$1"
    local recompiled="$2"
    local report_path="$3"

    ensure_built
    local module_path
    module_path="$(get_module_path)"

    echo "Running bytecode verification..."
    # Exit code 2 = mismatches found (expected, not an error)
    local rc=0
    "$JAVA_BIN" \
        --module-path "$module_path" \
        --module com.github.zomboiddecompiler/com.github.zomboiddecompiler.commands.Verify \
        "$original" "$recompiled" \
        --semantic --summary-only --json-report "$report_path" || rc=$?
    if [ "$rc" -ne 0 ] && [ "$rc" -ne 2 ]; then
        echo "Verification failed with exit code $rc"
        return "$rc"
    fi
}

generate_image() {
    local report_path="$1"
    local output_path="$2"
    local title="$3"

    ensure_venv

    echo "Generating progress image..."
    "$VENV_PYTHON" "$GENERATE_SCRIPT" "$report_path" "$output_path" "$title"
}
