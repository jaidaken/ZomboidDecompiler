#!/usr/bin/env bash
# Build 41 — run the game with recompiled classes.
#
# Recompiled classes are prepended to the classpath so they take priority
# over the original loose class files. Any classes that weren't recompiled
# fall back to the originals.
#
# Usage:  ./scripts/b41_run_recompiled.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-41/vanilla-game-41/projectzomboid"
RECOMPILED_DIR="$BUILDS_DIR/build-41/Recompiled-game"

if [ ! -d "$RECOMPILED_DIR/zombie" ]; then
    echo "No recompiled classes found at $RECOMPILED_DIR"
    echo "Run b41_recompile.sh first."
    exit 1
fi

echo "=== Build 41: Run Recompiled ==="
run_game "$GAME_DIR" "$RECOMPILED_DIR"
