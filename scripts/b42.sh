#!/usr/bin/env bash
# Build 42 — full pipeline orchestrator.
#
# Usage:
#   ./scripts/b42.sh                  # full pipeline: decompile + recompile + verify + image
#   ./scripts/b42.sh decompile        # decompile only
#   ./scripts/b42.sh recompile        # recompile only
#   ./scripts/b42.sh verify           # verify + generate progress image
#   ./scripts/b42.sh image            # regenerate image from existing report

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

do_image() {
    source "$SCRIPT_DIR/common.sh"
    REPORT_PATH="$PROJECT_DIR/progress/b42/report.json"
    IMAGE_PATH="$PROJECT_DIR/progress/b42/progress.png"
    TITLE="Build 42 — Decompilation Progress"
    generate_image "$REPORT_PATH" "$IMAGE_PATH" "$TITLE"
}

STEP="${1:-all}"
case "$STEP" in
    decompile)
        "$SCRIPT_DIR/b42_decompile.sh"
        ;;
    recompile)
        "$SCRIPT_DIR/b42_recompile.sh"
        ;;
    verify)
        "$SCRIPT_DIR/b42_verify.sh"
        ;;
    image)
        do_image
        ;;
    all)
        "$SCRIPT_DIR/b42_decompile.sh"
        "$SCRIPT_DIR/b42_recompile.sh"
        "$SCRIPT_DIR/b42_verify.sh"
        ;;
    *)
        echo "Usage: $0 [decompile|recompile|verify|image|all]"
        exit 1
        ;;
esac
