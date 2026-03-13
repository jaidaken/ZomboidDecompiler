#!/usr/bin/env bash
# Build 42 — run the game with modified recompiled classes.
#
# Usage:  ./scripts/b42_run_modified.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-42/vanilla-game-42/projectzomboid"
MODIFIED_GAME="$BUILDS_DIR/build-42/Modified-game"

if [ ! -d "$MODIFIED_GAME/zombie" ]; then
    echo "No modified recompiled classes found at $MODIFIED_GAME"
    echo "Run b42_recompile_modified.sh first."
    exit 1
fi

echo "=== Build 42: Run Modified ==="
run_game "$GAME_DIR" "$MODIFIED_GAME"
