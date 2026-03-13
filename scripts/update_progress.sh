#!/usr/bin/env bash
# Generate progress images for Build 41 and Build 42.
#
# Usage:
#   ./scripts/update_progress.sh <b41-original> <b41-recompiled> <b42-original> <b42-recompiled>
#
# The script runs the verify command with --json-report for each build,
# then generates treemap progress images from the reports.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VENV_PYTHON="$PROJECT_DIR/.venv/bin/python3"
GENERATE_SCRIPT="$SCRIPT_DIR/generate_progress_image.py"

usage() {
    echo "Usage: $0 <b41-original> <b41-recompiled> <b42-original> <b42-recompiled>"
    echo ""
    echo "  b41-original    Path to Build 41 original JAR or classes directory"
    echo "  b41-recompiled  Path to Build 41 recompiled classes directory"
    echo "  b42-original    Path to Build 42 original JAR or classes directory"
    echo "  b42-recompiled  Path to Build 42 recompiled classes directory"
    echo ""
    echo "You can also run individual builds:"
    echo "  $0 --b41 <original> <recompiled>"
    echo "  $0 --b42 <original> <recompiled>"
    exit 1
}

if [ $# -eq 0 ]; then
    usage
fi

case "$1" in
    --b41|--b42)
        [ $# -lt 3 ] && usage
        ;;
    *)
        [ $# -lt 4 ] && usage
        ;;
esac

# Check for venv
if [ ! -f "$VENV_PYTHON" ]; then
    echo "Creating Python venv..."
    python3 -m venv "$PROJECT_DIR/.venv"
    "$VENV_PYTHON" -m pip install --quiet matplotlib squarify
fi

run_verify() {
    local original="$1"
    local recompiled="$2"
    local report_path="$3"

    echo "Running bytecode verification..."
    "$PROJECT_DIR/build/install/ZomboidDecompiler/bin/ZomboidDecompiler" verify \
        "$original" "$recompiled" \
        --semantic --summary-only --json-report "$report_path" || true
}

generate_image() {
    local report_path="$1"
    local output_path="$2"
    local title="$3"

    echo "Generating progress image..."
    "$VENV_PYTHON" "$GENERATE_SCRIPT" "$report_path" "$output_path" "$title"
}

if [ "$1" = "--b41" ]; then
    run_verify "$2" "$3" "$PROJECT_DIR/progress/b41/report.json"
    generate_image "$PROJECT_DIR/progress/b41/report.json" "$PROJECT_DIR/progress/b41/progress.png" "Build 41 — Decompilation Progress"
elif [ "$1" = "--b42" ]; then
    run_verify "$2" "$3" "$PROJECT_DIR/progress/b42/report.json"
    generate_image "$PROJECT_DIR/progress/b42/report.json" "$PROJECT_DIR/progress/b42/progress.png" "Build 42 — Decompilation Progress"
else
    # Both builds
    run_verify "$1" "$2" "$PROJECT_DIR/progress/b41/report.json"
    generate_image "$PROJECT_DIR/progress/b41/report.json" "$PROJECT_DIR/progress/b41/progress.png" "Build 41 — Decompilation Progress"

    run_verify "$3" "$4" "$PROJECT_DIR/progress/b42/report.json"
    generate_image "$PROJECT_DIR/progress/b42/report.json" "$PROJECT_DIR/progress/b42/progress.png" "Build 42 — Decompilation Progress"
fi

echo "Done!"
