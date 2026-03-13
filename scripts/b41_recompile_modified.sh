#!/usr/bin/env bash
# Build 41 — recompile modified source back to .class files.
#
# Usage:  ./scripts/b41_recompile_modified.sh

source "$(dirname "$0")/common.sh"

GAME_DIR="$BUILDS_DIR/build-41/vanilla-game-41/projectzomboid"
MODIFIED_DIR="$BUILDS_DIR/build-41/Modified-src"
MODIFIED_GAME="$BUILDS_DIR/build-41/Modified-game"
SOURCE_DIR="$MODIFIED_DIR/source"

if [ ! -d "$SOURCE_DIR/zombie" ]; then
    echo "No modified source found at $SOURCE_DIR"
    echo "Run b41_copy_source.sh first, then edit the source."
    exit 1
fi

echo "=== Build 41: Recompile Modified (Java 17) ==="
run_recompile "$SOURCE_DIR" "$MODIFIED_GAME" "$GAME_DIR"
echo "Done!"
