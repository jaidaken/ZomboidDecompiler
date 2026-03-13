#!/usr/bin/env bash
# Build 42 — run the game with recompiled classes.
#
# Recompiled classes are prepended to the classpath so they take priority
# over projectzomboid.jar. Any classes that weren't recompiled fall back
# to the originals in the JAR.
#
# Usage:  ./scripts/b42_run_recompiled.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-42/vanilla-game-42/projectzomboid"
RECOMPILED_DIR="$BUILDS_DIR/build-42/Recompiled-game"

if [ ! -d "$RECOMPILED_DIR/zombie" ]; then
    echo "No recompiled classes found at $RECOMPILED_DIR"
    echo "Run b42_recompile.sh first."
    exit 1
fi

echo "=== Build 42: Run Recompiled ==="
run_game "$GAME_DIR" "$RECOMPILED_DIR"
