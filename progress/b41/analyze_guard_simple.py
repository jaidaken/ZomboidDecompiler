#!/usr/bin/env python3
"""
Find the simplest GUARD_CLAUSE_INVERSION cases to understand exact instruction patterns.

Part 1: 5 methods with smallest origInsnCount where |origInsnCount - recompInsnCount| <= 5
Part 2: 5 methods where origInsnCount == recompInsnCount (exact same size), smallest first
"""

import json

REPORT_PATH = "/home/jaidaken/optizomb/ZomboidDecompiler/progress/b41/report.json"


def load_guard_methods():
    with open(REPORT_PATH) as f:
        data = json.load(f)

    results = []
    for unit in data["units"]:
        class_name = unit["name"]
        for m in unit.get("methods", []):
            if m.get("category") == "GUARD_CLAUSE_INVERSION":
                results.append({
                    "class": class_name,
                    "name": m["name"],
                    "descriptor": m.get("descriptor", ""),
                    "origInsnCount": m.get("origInsnCount", 0),
                    "recompInsnCount": m.get("recompInsnCount", 0),
                    "firstDiffIndex": m.get("firstDiffIndex", 0),
                    "diffDescription": m.get("diffDescription", ""),
                    "origContext": m.get("origContext", []),
                    "recompContext": m.get("recompContext", []),
                })
    return results


def print_method_full(m, index):
    diff = abs(m["origInsnCount"] - m["recompInsnCount"])
    print(f"--- Method #{index} ---")
    print(f"  Class:           {m['class']}")
    print(f"  Method:          {m['name']}{m['descriptor']}")
    print(f"  origInsnCount:   {m['origInsnCount']}")
    print(f"  recompInsnCount: {m['recompInsnCount']}")
    print(f"  diff:            {diff}")
    print(f"  firstDiffIndex:  {m['firstDiffIndex']}")
    print(f"  diffDescription: {m['diffDescription']}")
    print()
    print("  origContext (ALL lines):")
    for line in m["origContext"]:
        print(f"    {line}")
    print()
    print("  recompContext (ALL lines):")
    for line in m["recompContext"]:
        print(f"    {line}")
    print()


def main():
    methods = load_guard_methods()
    print(f"Total GUARD_CLAUSE_INVERSION methods: {len(methods)}")
    print()

    # ── Part 1: Smallest instruction counts where diff <= 5 ──
    close_methods = [
        m for m in methods
        if abs(m["origInsnCount"] - m["recompInsnCount"]) <= 5
    ]
    close_methods.sort(
        key=lambda m: (
            m["origInsnCount"],
            abs(m["origInsnCount"] - m["recompInsnCount"]),
        )
    )

    print("=" * 90)
    print("PART 1: 5 GUARD_CLAUSE_INVERSION with SMALLEST origInsnCount, diff <= 5")
    print("=" * 90)
    print(f"  (Total methods with diff <= 5: {len(close_methods)})")
    print()

    for i, m in enumerate(close_methods[:5], 1):
        print_method_full(m, i)

    # ── Part 2: origInsnCount == recompInsnCount, smallest first ──
    exact_methods = [
        m for m in methods
        if m["origInsnCount"] == m["recompInsnCount"]
    ]
    exact_methods.sort(key=lambda m: m["origInsnCount"])

    print("=" * 90)
    print("PART 2: 5 GUARD_CLAUSE_INVERSION where origInsnCount == recompInsnCount")
    print("=" * 90)
    print(f"  (Total methods with exact match: {len(exact_methods)})")
    print()

    for i, m in enumerate(exact_methods[:5], 1):
        print_method_full(m, i)


if __name__ == "__main__":
    main()
