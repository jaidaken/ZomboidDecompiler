#!/usr/bin/env bash
# Build 41 — verify recompiled bytecode and generate progress image.
#
# Usage:  ./scripts/b41_verify.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-41/vanilla-game-41/projectzomboid"
RECOMPILED_DIR="$BUILDS_DIR/build-41/Recompiled-game"
REPORT_PATH="$PROJECT_DIR/progress/b41/report.json"
IMAGE_PATH="$PROJECT_DIR/progress/b41/progress.png"
TITLE="Build 41 — Decompilation Progress"

if [ ! -d "$RECOMPILED_DIR/zombie" ]; then
    echo "No recompiled classes found at $RECOMPILED_DIR"
    echo "Run b41_recompile.sh first."
    exit 1
fi

echo "=== Build 41: Verify ==="
mkdir -p "$(dirname "$REPORT_PATH")"
run_verify "$GAME_DIR" "$RECOMPILED_DIR" "$REPORT_PATH"

echo "=== Build 41: Progress Image ==="
generate_image "$REPORT_PATH" "$IMAGE_PATH" "$TITLE"
echo "Done!"
