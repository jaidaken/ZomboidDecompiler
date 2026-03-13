#!/usr/bin/env bash
# Build 42 — decompile, recompile, verify, and generate progress image.
#
# Usage:
#   ./scripts/b42.sh                  # full pipeline: decompile + verify + image
#   ./scripts/b42.sh decompile        # decompile only
#   ./scripts/b42.sh verify           # verify + generate progress image only
#   ./scripts/b42.sh image            # regenerate image from existing report

source "$(dirname "$0")/common.sh"

# Build 42 paths (JAR-based)
GAME_DIR="$BUILDS_DIR/build-42/vanilla-game-42/projectzomboid"
GAME_JAR="$GAME_DIR/projectzomboid.jar"
DECOMPILED_DIR="$BUILDS_DIR/build-42/Decompiled-src"
RECOMPILED_DIR="$BUILDS_DIR/build-42/Recompiled-game"
REPORT_PATH="$PROJECT_DIR/progress/b42/report.json"
IMAGE_PATH="$PROJECT_DIR/progress/b42/progress.png"
TITLE="Build 42 — Decompilation Progress"

do_decompile() {
    run_decompile "$GAME_DIR" "$DECOMPILED_DIR" --jar-game
}

do_verify() {
    run_verify "$GAME_JAR" "$RECOMPILED_DIR" "$REPORT_PATH"
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
