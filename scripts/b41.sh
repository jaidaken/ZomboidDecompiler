#!/usr/bin/env bash
# Build 41 — full pipeline orchestrator.
#
# Usage:
#   ./scripts/b41.sh                  # full pipeline: decompile + recompile + verify + image
#   ./scripts/b41.sh decompile        # decompile only
#   ./scripts/b41.sh recompile        # recompile only
#   ./scripts/b41.sh verify           # verify + generate progress image
#   ./scripts/b41.sh image            # regenerate image from existing report

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

do_image() {
    source "$SCRIPT_DIR/common.sh"
    REPORT_PATH="$PROJECT_DIR/progress/b41/report.json"
    IMAGE_PATH="$PROJECT_DIR/progress/b41/progress.png"
    TITLE="Build 41 — Decompilation Progress"
    generate_image "$REPORT_PATH" "$IMAGE_PATH" "$TITLE"
}

STEP="${1:-all}"
case "$STEP" in
    decompile)
        "$SCRIPT_DIR/b41_decompile.sh"
        ;;
    recompile)
        "$SCRIPT_DIR/b41_recompile.sh"
        ;;
    verify)
        "$SCRIPT_DIR/b41_verify.sh"
        ;;
    image)
        do_image
        ;;
    all)
        "$SCRIPT_DIR/b41_decompile.sh"
        "$SCRIPT_DIR/b41_recompile.sh"
        "$SCRIPT_DIR/b41_verify.sh"
        ;;
    *)
        echo "Usage: $0 [decompile|recompile|verify|image|all]"
        exit 1
        ;;
esac
