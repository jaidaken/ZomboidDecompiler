#!/usr/bin/env bash
# Build 41 — decompile game classes to Java source.
#
# Usage:  ./scripts/b41_decompile.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-41/vanilla-game-41/projectzomboid"
DECOMPILED_DIR="$BUILDS_DIR/build-41/Decompiled-src"

echo "=== Build 41: Decompile ==="
run_decompile "$GAME_DIR" "$DECOMPILED_DIR" --jar-game
echo "Done!"
