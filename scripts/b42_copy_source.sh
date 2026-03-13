#!/usr/bin/env bash
# Build 42 — copy decompiled source to Modified-src for editing.
#
# Usage:  ./scripts/b42_copy_source.sh

source "$(dirname "$0")/common.sh"

DECOMPILED_DIR="$BUILDS_DIR/build-42/Decompiled-src"
MODIFIED_DIR="$BUILDS_DIR/build-42/Modified-src"

if [ ! -d "$DECOMPILED_DIR/source/zombie" ]; then
    echo "No decompiled source found at $DECOMPILED_DIR/source"
    echo "Run b42_decompile.sh first."
    exit 1
fi

echo "=== Build 42: Copy Source to Modified-src ==="
rm -rf "$MODIFIED_DIR"
mkdir -p "$MODIFIED_DIR"

cp -a "$DECOMPILED_DIR/." "$MODIFIED_DIR/"

total=$(command find "$MODIFIED_DIR/source" -name "*.java" 2>/dev/null | wc -l)
echo "  Copied $total source files to $MODIFIED_DIR"
echo "Done!"
