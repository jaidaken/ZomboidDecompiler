#!/usr/bin/env bash
# Build 42 — run the vanilla (original) game.
#
# Usage:  ./scripts/b42_run_vanilla.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-42/vanilla-game-42/projectzomboid"

echo "=== Build 42: Run Vanilla ==="
run_game "$GAME_DIR"
