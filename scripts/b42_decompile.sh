#!/usr/bin/env bash
# Build 42 — decompile game classes to Java source.
#
# Usage:  ./scripts/b42_decompile.sh

source "$(dirname "$0")/common.sh"

# Build 42 targets Java 25
if [ -x "$ZULU25_HOME/bin/java" ]; then
    JAVA_BIN="$ZULU25_HOME/bin/java"
fi

GAME_DIR="$BUILDS_DIR/build-42/vanilla-game-42/projectzomboid"
DECOMPILED_DIR="$BUILDS_DIR/build-42/Decompiled-src"

echo "=== Build 42: Decompile (Java 25) ==="
run_decompile "$GAME_DIR" "$DECOMPILED_DIR" --jar-game --build-version b42
echo "Done!"
