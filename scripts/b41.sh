#!/usr/bin/env bash
# Build 41 — decompile, recompile, verify, and generate progress image.
#
# Usage:
#   ./scripts/b41.sh                  # full pipeline: decompile + verify + image
#   ./scripts/b41.sh decompile        # decompile only
#   ./scripts/b41.sh verify           # verify + generate progress image only
#   ./scripts/b41.sh image            # regenerate image from existing report

source "$(dirname "$0")/common.sh"

# Build 41 paths (loose class files)
GAME_DIR="$BUILDS_DIR/build-41/vanilla-game-41/projectzomboid"
DECOMPILED_DIR="$BUILDS_DIR/build-41/Decompiled-src"
RECOMPILED_DIR="$BUILDS_DIR/build-41/Recompiled-game"
REPORT_PATH="$PROJECT_DIR/progress/b41/report.json"
IMAGE_PATH="$PROJECT_DIR/progress/b41/progress.png"
TITLE="Build 41 — Decompilation Progress"

do_decompile() {
    run_decompile "$GAME_DIR" "$DECOMPILED_DIR" --jar-game
}

do_verify() {
    run_verify "$GAME_DIR" "$RECOMPILED_DIR" "$REPORT_PATH"
}

do_image() {
    generate_image "$REPORT_PATH" "$IMAGE_PATH" "$TITLE"
}

STEP="${1:-all}"
case "$STEP" in
    decompile)
        do_decompile
        ;;
    verify)
        do_verify
        do_image
        ;;
    image)
        do_image
        ;;
    all)
        do_decompile
        do_verify
        do_image
        ;;
    *)
        echo "Usage: $0 [decompile|verify|image|all]"
        exit 1
        ;;
esac

echo "Done!"
