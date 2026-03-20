#!/usr/bin/env bash
# Build 42 — verify recompiled bytecode and generate progress image.
#
# Usage:  ./scripts/b42_verify.sh

source "$(dirname "$0")/common.sh"

GAME_JAR="$BUILDS_DIR/build-42/vanilla-game-42/projectzomboid/projectzomboid.jar"
RECOMPILED_DIR="$BUILDS_DIR/build-42/Recompiled-game"
SOURCE_DIR="$BUILDS_DIR/build-42/Decompiled-src/source"
REPORT_PATH="$PROJECT_DIR/progress/b42/report.json"
IMAGE_PATH="$PROJECT_DIR/progress/b42/progress.png"
TITLE="Build 42 — Decompilation Progress"

if [ ! -d "$RECOMPILED_DIR/zombie" ]; then
    echo "No recompiled classes found at $RECOMPILED_DIR"
    echo "Run b42_recompile.sh first."
    exit 1
fi

echo "=== Build 42: Verify ==="
mkdir -p "$(dirname "$REPORT_PATH")"
run_verify "$GAME_JAR" "$RECOMPILED_DIR" "$REPORT_PATH"

echo "=== Build 42: Progress Image ==="
generate_image "$REPORT_PATH" "$IMAGE_PATH" "$TITLE" "$SOURCE_DIR"
echo "Done!"
