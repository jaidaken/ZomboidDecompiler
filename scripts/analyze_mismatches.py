#!/usr/bin/env python3
"""
Analyze the JSON verification report to understand dominant mismatch patterns.

Works with:
  - Enhanced report format (with per-method category/diffDescription/context)
  - Legacy report format (class-level aggregates only -- limited analysis)

Usage:
  python3 scripts/analyze_mismatches.py progress/b41/report.json
"""

import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path


def load_report(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def has_method_detail(report: dict) -> bool:
    """Check if any unit has the 'methods' array with category data."""
    for unit in report["units"]:
        if "methods" in unit:
            return True
    return False


# ─── Enhanced report analysis (method-level detail) ───


def collect_mismatched_methods(report: dict) -> list[dict]:
    """Collect all mismatched methods across all units, annotated with class name."""
    methods = []
    for unit in report["units"]:
        if "methods" not in unit:
            continue
        for m in unit["methods"]:
            methods.append({**m, "className": unit["name"]})
    return methods


def analyze_categories(methods: list[dict]) -> None:
    """Task 1: Count per category across all mismatched methods."""
    cats = Counter(m["category"] for m in methods)
    total = len(methods)

    print("=" * 70)
    print(f"  MISMATCH CATEGORY BREAKDOWN  ({total} mismatched methods)")
    print("=" * 70)
    for cat, count in cats.most_common():
        pct = 100.0 * count / total
        bar = "#" * int(pct / 2)
        print(f"  {cat:<30s} {count:>4d}  ({pct:5.1f}%)  {bar}")
    print()


def analyze_top_classes(methods: list[dict], top_n: int = 5) -> None:
    """Task 2: Top N most-mismatched classes with method names and categories."""
    by_class = defaultdict(list)
    for m in methods:
        by_class[m["className"]].append(m)

    ranked = sorted(by_class.items(), key=lambda kv: len(kv[1]), reverse=True)

    print("=" * 70)
    print(f"  TOP {top_n} MOST-MISMATCHED CLASSES")
    print("=" * 70)
    for class_name, class_methods in ranked[:top_n]:
        pretty = class_name.replace("/", ".")
        print(f"\n  {pretty}  ({len(class_methods)} mismatched methods)")
        print(f"  {'-' * (len(pretty) + 30)}")
        for m in class_methods:
            print(f"    {m['name']}{m['descriptor']}")
            print(f"      category: {m['category']}")
            if m.get("diffDescription"):
                desc = m["diffDescription"]
                if len(desc) > 90:
                    desc = desc[:87] + "..."
                print(f"      diff:     {desc}")
    print()


def analyze_other_subpatterns(methods: list[dict]) -> None:
    """Task 3: For OTHER category, group diffDescription by common patterns."""
    others = [m for m in methods if m["category"] == "OTHER"]
    if not others:
        print("=" * 70)
        print("  OTHER CATEGORY SUB-PATTERNS")
        print("=" * 70)
        print("  No methods in OTHER category.\n")
        return

    # Define pattern keywords to look for in diffDescription and context
    pattern_rules = [
        ("ALOAD/ASTORE reorder", re.compile(r"ALOAD|ASTORE", re.IGNORECASE)),
        ("ILOAD/ISTORE reorder", re.compile(r"ILOAD|ISTORE", re.IGNORECASE)),
        ("GOTO difference", re.compile(r"GOTO", re.IGNORECASE)),
        ("GETFIELD vs GETSTATIC", re.compile(r"GETFIELD|GETSTATIC", re.IGNORECASE)),
        ("INVOKE difference", re.compile(r"INVOKE", re.IGNORECASE)),
        ("RETURN variant", re.compile(r"RETURN|ARETURN|IRETURN|DRETURN|FRETURN|LRETURN", re.IGNORECASE)),
        ("Array operation", re.compile(r"ALOAD|AALOAD|AASTORE|NEWARRAY|ANEWARRAY", re.IGNORECASE)),
        ("Arithmetic", re.compile(r"IADD|ISUB|IMUL|IDIV|IREM|INEG|ISHL|ISHR|IUSHR|IAND|IOR|IXOR|FADD|FSUB|FMUL|FDIV|DADD|DSUB|DMUL|DDIV|LADD|LSUB|LMUL|LDIV", re.IGNORECASE)),
        ("Comparison", re.compile(r"LCMP|FCMP|DCMP|INSTANCEOF", re.IGNORECASE)),
        ("NEW/INIT", re.compile(r"\bNEW\b|INIT", re.IGNORECASE)),
        ("POP/SWAP/NOP", re.compile(r"\bPOP\b|\bSWAP\b|\bNOP\b", re.IGNORECASE)),
    ]

    # Categorize each OTHER method by looking at diffDescription + origContext + recompContext
    sub_counts = Counter()
    sub_examples = defaultdict(list)
    unclassified = []

    for m in others:
        combined_text = m.get("diffDescription", "")
        for ctx_line in m.get("origContext", []):
            combined_text += " " + ctx_line
        for ctx_line in m.get("recompContext", []):
            combined_text += " " + ctx_line

        matched_pattern = None
        for pattern_name, regex in pattern_rules:
            if regex.search(combined_text):
                matched_pattern = pattern_name
                break

        if matched_pattern:
            sub_counts[matched_pattern] += 1
            if len(sub_examples[matched_pattern]) < 2:
                pretty_class = m["className"].replace("/", ".")
                sub_examples[matched_pattern].append(
                    f"{pretty_class}.{m['name']}{m['descriptor']}"
                )
        else:
            unclassified.append(m)

    total_other = len(others)
    print("=" * 70)
    print(f"  OTHER CATEGORY SUB-PATTERNS  ({total_other} methods)")
    print("=" * 70)

    for pattern_name, count in sub_counts.most_common():
        pct = 100.0 * count / total_other
        print(f"  {pattern_name:<30s} {count:>4d}  ({pct:5.1f}%)")
        for ex in sub_examples[pattern_name]:
            print(f"    e.g. {ex}")

    if unclassified:
        print(f"\n  Truly unclassified:            {len(unclassified):>4d}  "
              f"({100.0 * len(unclassified) / total_other:5.1f}%)")
        for m in unclassified[:5]:
            pretty_class = m["className"].replace("/", ".")
            desc = m.get("diffDescription", "(no description)")
            if len(desc) > 80:
                desc = desc[:77] + "..."
            print(f"    {pretty_class}.{m['name']}")
            print(f"      {desc}")
    print()


def analyze_guard_clause_patterns(methods: list[dict]) -> None:
    """Task 4: For guard clause inversions, check class/package patterns."""
    inversions = [m for m in methods if m["category"] == "GUARD_CLAUSE_INVERSION"]
    if not inversions:
        print("=" * 70)
        print("  GUARD_CLAUSE_INVERSION -- PACKAGE/CLASS PATTERNS")
        print("=" * 70)
        print("  No guard clause inversions found.\n")
        return

    # Group by top-level package (e.g., zombie/core, zombie/iso, etc.)
    by_package = Counter()
    by_class = Counter()
    for m in inversions:
        class_name = m["className"]
        by_class[class_name] += 1
        parts = class_name.split("/")
        if len(parts) >= 2:
            pkg = "/".join(parts[:2])
        else:
            pkg = parts[0]
        by_package[pkg] += 1

    total = len(inversions)
    print("=" * 70)
    print(f"  GUARD_CLAUSE_INVERSION -- PACKAGE/CLASS PATTERNS  ({total} methods)")
    print("=" * 70)

    print("\n  By package:")
    for pkg, count in by_package.most_common(15):
        pct = 100.0 * count / total
        print(f"    {pkg.replace('/', '.'):<40s} {count:>4d}  ({pct:5.1f}%)")

    print("\n  Top classes:")
    for cls, count in by_class.most_common(10):
        print(f"    {cls.replace('/', '.'):<55s} {count:>3d}")
    print()


# ─── Legacy report analysis (class-level only) ───


def analyze_legacy_report(report: dict) -> None:
    """Provide what analysis we can from the class-level aggregated data."""
    units = report["units"]
    measures = report["measures"]

    mismatched = [u for u in units if u["status"] == "MISMATCH"]
    missing = [u for u in units if u["status"] == "MISSING_RECOMP"]

    total_mismatched_methods = sum(
        u["total_methods"] - u["matched_methods"] for u in mismatched
    )

    print("=" * 70)
    print("  REPORT OVERVIEW (class-level aggregates only)")
    print("=" * 70)
    print(f"  Total classes:         {measures['total_classes']}")
    print(f"  Matched classes:       {measures['matched_classes']}")
    print(f"  Mismatched classes:    {len(mismatched)}")
    print(f"  Missing classes:       {len(missing)}")
    print(f"  Total methods:         {measures['total_methods']}")
    print(f"  Matched methods:       {measures['matched_methods']}")
    print(f"  Mismatched methods:    {total_mismatched_methods}")
    print(f"  Code match:            {measures['matched_code_percent']:.2f}%")
    print(f"  Function match:        {measures['matched_function_percent']:.2f}%")
    print()

    # Top mismatched classes by method count
    ranked = sorted(
        mismatched,
        key=lambda u: u["total_methods"] - u["matched_methods"],
        reverse=True,
    )

    print("=" * 70)
    print("  TOP 15 CLASSES BY MISMATCHED METHOD COUNT")
    print("=" * 70)
    for u in ranked[:15]:
        mis = u["total_methods"] - u["matched_methods"]
        pretty = u["name"].replace("/", ".")
        print(
            f"  {pretty:<55s} {mis:>3d}/{u['total_methods']:>3d} methods  "
            f"({u['matched_code_percent']:.1f}% code)"
        )
    print()

    # Top mismatched classes by instruction gap
    ranked_insn = sorted(
        mismatched,
        key=lambda u: u["total_instructions"] - u["matched_instructions"],
        reverse=True,
    )

    print("=" * 70)
    print("  TOP 15 CLASSES BY UNMATCHED INSTRUCTION COUNT")
    print("=" * 70)
    for u in ranked_insn[:15]:
        gap = u["total_instructions"] - u["matched_instructions"]
        pretty = u["name"].replace("/", ".")
        print(
            f"  {pretty:<55s} {gap:>5d} insns unmatched  "
            f"({u['matched_code_percent']:.1f}%)"
        )
    print()

    # Package-level grouping
    by_package = defaultdict(lambda: {"mismatch_methods": 0, "total_methods": 0, "classes": 0})
    for u in mismatched:
        parts = u["name"].split("/")
        pkg = "/".join(parts[:2]) if len(parts) >= 2 else parts[0]
        mis = u["total_methods"] - u["matched_methods"]
        by_package[pkg]["mismatch_methods"] += mis
        by_package[pkg]["total_methods"] += u["total_methods"]
        by_package[pkg]["classes"] += 1

    ranked_pkg = sorted(
        by_package.items(),
        key=lambda kv: kv[1]["mismatch_methods"],
        reverse=True,
    )

    print("=" * 70)
    print("  MISMATCHED METHODS BY PACKAGE")
    print("=" * 70)
    for pkg, data in ranked_pkg[:20]:
        pretty = pkg.replace("/", ".")
        print(
            f"  {pretty:<35s} {data['mismatch_methods']:>4d} mismatched methods  "
            f"across {data['classes']:>3d} classes"
        )
    print()


def main() -> None:
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <report.json>")
        sys.exit(1)

    report_path = sys.argv[1]
    if not Path(report_path).exists():
        print(f"File not found: {report_path}")
        sys.exit(1)

    report = load_report(report_path)

    if has_method_detail(report):
        methods = collect_mismatched_methods(report)
        print(f"\nLoaded {len(methods)} mismatched methods with detail.\n")

        analyze_categories(methods)
        analyze_top_classes(methods, top_n=5)
        analyze_other_subpatterns(methods)
        analyze_guard_clause_patterns(methods)
    else:
        print("\n  NOTE: This report has class-level aggregates only (no method detail).")
        print("  To get full method-level analysis with categories, re-run verify:")
        print("    ./scripts/b41.sh verify")
        print("  The enhanced Verify.java now writes method-level detail to the JSON.\n")

        analyze_legacy_report(report)


if __name__ == "__main__":
    main()
