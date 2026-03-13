#!/usr/bin/env python3
"""Extract origContext and recompContext for mismatched methods in targeted classes."""

import json
import sys

REPORT_PATH = "/home/jaidaken/optizomb/ZomboidDecompiler/progress/b41/report.json"

TARGET_CLASSES = {
    "zombie/network/LoginQueue": 3,       # Extract 3 methods
    "zombie/core/Collections/ZomboidHashMap": None,  # All methods
    "zombie/inventory/ItemContainer": None,          # All methods
}

def main():
    with open(REPORT_PATH) as f:
        data = json.load(f)

    for unit in data["units"]:
        name = unit["name"]
        if name not in TARGET_CLASSES:
            continue

        limit = TARGET_CLASSES[name]
        methods = unit.get("methods", [])

        print("=" * 80)
        print(f"CLASS: {name}")
        print(f"  total_methods: {unit['total_methods']}, matched: {unit['matched_methods']}")
        print(f"  mismatched methods in report: {len(methods)}")
        print("=" * 80)

        for i, m in enumerate(methods):
            if limit is not None and i >= limit:
                break

            diff = m["recompInsnCount"] - m["origInsnCount"]
            print(f"\n  Method: {m['name']}{m['descriptor']}")
            print(f"    recomp={m['recompInsnCount']}, orig={m['origInsnCount']}, diff={diff:+d}")
            print(f"    firstDiffIndex={m['firstDiffIndex']}, category={m['category']}")
            print(f"    diffDescription: {m['diffDescription']}")

            print(f"\n    --- recompContext ({len(m['recompContext'])} lines) ---")
            for line in m["recompContext"]:
                print(f"      {line}")

            print(f"\n    --- origContext ({len(m['origContext'])} lines) ---")
            for line in m["origContext"]:
                print(f"      {line}")

        print()

if __name__ == "__main__":
    main()
