#!/usr/bin/env bash
# Build 42 — recompile decompiled Java source back to .class files.
#
# Usage:  ./scripts/b42_recompile.sh

source "$(dirname "$0")/common.sh"

# Build 42 targets Java 25
if [ -x "$ZULU25_HOME/bin/java" ]; then
    JAVA_BIN="$ZULU25_HOME/bin/java"
    JAVAC_BIN="$ZULU25_HOME/bin/javac"
fi
JAVAC_SOURCE_VERSION=25
JAVAC_TARGET_VERSION=25
ECJ_JAR="$ECJ_JAR_25"

GAME_DIR="$BUILDS_DIR/build-42/vanilla-game-42/projectzomboid"
DECOMPILED_DIR="$BUILDS_DIR/build-42/Decompiled-src"
RECOMPILED_DIR="$BUILDS_DIR/build-42/Recompiled-game"
SOURCE_DIR="$DECOMPILED_DIR/source"
GAME_JAR="$DECOMPILED_DIR/projectzomboid.jar"

if [ ! -d "$SOURCE_DIR/zombie" ]; then
    echo "No decompiled source found at $SOURCE_DIR"
    echo "Run b42_decompile.sh first."
    exit 1
fi

echo "=== Build 42: Recompile (Java 25) ==="
# Pass the copied projectzomboid.jar as extra classpath (contains all game classes)
run_recompile "$SOURCE_DIR" "$RECOMPILED_DIR" "$GAME_DIR" "$GAME_JAR"
echo "Done!"
