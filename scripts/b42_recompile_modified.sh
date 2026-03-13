#!/usr/bin/env bash
# Build 42 — recompile modified source back to .class files.
#
# Usage:  ./scripts/b42_recompile_modified.sh

source "$(dirname "$0")/common.sh"

# Build 42 targets Java 25
if [ -x "$ZULU25_HOME/bin/java" ]; then
    JAVA_BIN="$ZULU25_HOME/bin/java"
    JAVAC_BIN="$ZULU25_HOME/bin/javac"
fi
JAVAC_SOURCE_VERSION=25
JAVAC_TARGET_VERSION=25

GAME_DIR="$BUILDS_DIR/build-42/vanilla-game-42/projectzomboid"
MODIFIED_DIR="$BUILDS_DIR/build-42/Modified-src"
MODIFIED_GAME="$BUILDS_DIR/build-42/Modified-game"
SOURCE_DIR="$MODIFIED_DIR/source"
GAME_JAR="$MODIFIED_DIR/projectzomboid.jar"

if [ ! -d "$SOURCE_DIR/zombie" ]; then
    echo "No modified source found at $SOURCE_DIR"
    echo "Run b42_copy_source.sh first, then edit the source."
    exit 1
fi

echo "=== Build 42: Recompile Modified (Java 25) ==="
run_recompile "$SOURCE_DIR" "$MODIFIED_GAME" "$GAME_DIR" "$GAME_JAR"
echo "Done!"
