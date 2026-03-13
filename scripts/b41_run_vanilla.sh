#!/usr/bin/env bash
# Build 41 — run the vanilla (original) game.
#
# Usage:  ./scripts/b41_run_vanilla.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-41/vanilla-game-41/projectzomboid"

echo "=== Build 41: Run Vanilla ==="
run_game "$GAME_DIR"
