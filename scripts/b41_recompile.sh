#!/usr/bin/env bash
# Build 41 — recompile decompiled Java source back to .class files.
#
# Usage:  ./scripts/b41_recompile.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-41/vanilla-game-41/projectzomboid"
DECOMPILED_DIR="$BUILDS_DIR/build-41/Decompiled-src"
RECOMPILED_DIR="$BUILDS_DIR/build-41/Recompiled-game"
SOURCE_DIR="$DECOMPILED_DIR/source"

if [ ! -d "$SOURCE_DIR/zombie" ]; then
    echo "No decompiled source found at $SOURCE_DIR"
    echo "Run b41_decompile.sh first."
    exit 1
fi

echo "=== Build 41: Recompile ==="
run_recompile "$SOURCE_DIR" "$RECOMPILED_DIR" "$GAME_DIR"
echo "Done!"
