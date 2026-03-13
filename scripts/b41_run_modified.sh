#!/usr/bin/env bash
# Build 41 — run the game with modified recompiled classes.
#
# Usage:  ./scripts/b41_run_modified.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-41/vanilla-game-41/projectzomboid"
MODIFIED_GAME="$BUILDS_DIR/build-41/Modified-game"

if [ ! -d "$MODIFIED_GAME/zombie" ]; then
    echo "No modified recompiled classes found at $MODIFIED_GAME"
    echo "Run b41_recompile_modified.sh first."
    exit 1
fi

echo "=== Build 41: Run Modified ==="
run_game "$GAME_DIR" "$MODIFIED_GAME"
