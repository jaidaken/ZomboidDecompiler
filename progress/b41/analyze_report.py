#!/usr/bin/env python3
"""Analyze the enhanced verification report for b41."""

import json
import re
from collections import Counter, defaultdict


def load_report(path):
    with open(path) as f:
        return json.load(f)


def extract_methods_with_class(data):
    """Yield (class_name, method_dict) for every mismatched method."""
    for unit in data["units"]:
        class_name = unit["name"]
        for method in unit.get("methods", []):
            yield class_name, method


def extract_first_divergent_pair(class_name, method):
    """For OTHER category, extract the first divergent instruction pair from contexts."""
    orig_ctx = method.get("origContext", [])
    recomp_ctx = method.get("recompContext", [])
    first_diff = method.get("firstDiffIndex", None)

    if not orig_ctx or not recomp_ctx or first_diff is None:
        return "no context available"

    # Find the instruction at firstDiffIndex in each context
    def find_insn_at_index(ctx, idx):
        for line in ctx:
            match = re.match(r"#(\d+)\s+(.*)", line)
            if match and int(match.group(1)) == idx:
                # Normalize: strip operand details to get the opcode + general shape
                insn_text = match.group(2).strip()
                return insn_text
        return None

    orig_insn = find_insn_at_index(orig_ctx, first_diff)
    recomp_insn = find_insn_at_index(recomp_ctx, first_diff)

    if orig_insn and recomp_insn:
        # Extract just the opcode from each
        orig_op = orig_insn.split()[0] if orig_insn else "?"
        recomp_op = recomp_insn.split()[0] if recomp_insn else "?"
        return f"{orig_op} vs {recomp_op}"
    elif orig_insn:
        orig_op = orig_insn.split()[0]
        return f"{orig_op} vs (missing)"
    elif recomp_insn:
        recomp_op = recomp_insn.split()[0]
        return f"(missing) vs {recomp_op}"
    else:
        return "divergent index not in context window"


def main():
    data = load_report("/home/jaidaken/optizomb/ZomboidDecompiler/progress/b41/report.json")

    all_methods = list(extract_methods_with_class(data))

    # ── 1. Count mismatched methods per category ──────────────────────
    category_counts = Counter()
    for _, method in all_methods:
        category_counts[method.get("category", "UNKNOWN")] += 1

    print("=" * 70)
    print("1. MISMATCHED METHODS PER CATEGORY")
    print("=" * 70)
    total_mismatched = sum(category_counts.values())
    for cat, count in category_counts.most_common():
        pct = 100.0 * count / total_mismatched if total_mismatched else 0
        print(f"  {cat:<30s} {count:>5d}  ({pct:5.1f}%)")
    print(f"  {'TOTAL':<30s} {total_mismatched:>5d}")
    print()

    # ── 2. OTHER category: group by first divergent instruction pair ──
    print("=" * 70)
    print("2. OTHER CATEGORY: FIRST DIVERGENT INSTRUCTION PATTERNS")
    print("=" * 70)
    other_patterns = Counter()
    other_examples = defaultdict(list)
    for class_name, method in all_methods:
        if method.get("category") != "OTHER":
            continue
        pattern = extract_first_divergent_pair(class_name, method)
        other_patterns[pattern] += 1
        if len(other_examples[pattern]) < 2:
            other_examples[pattern].append(
                f"{class_name}.{method['name']}{method.get('descriptor', '')}"
            )

    for pattern, count in other_patterns.most_common(20):
        examples = other_examples[pattern]
        print(f"\n  [{count:>3d}x] {pattern}")
        for ex in examples:
            print(f"         e.g. {ex}")
    print()

    # ── 3. Top 10 categories: 3 example methods each ─────────────────
    print("=" * 70)
    print("3. TOP 10 CATEGORIES WITH EXAMPLE METHODS")
    print("=" * 70)
    category_examples = defaultdict(list)
    for class_name, method in all_methods:
        cat = method.get("category", "UNKNOWN")
        if len(category_examples[cat]) < 3:
            category_examples[cat].append({
                "class": class_name,
                "method": method["name"],
                "descriptor": method.get("descriptor", ""),
                "diffDescription": method.get("diffDescription", ""),
            })

    for cat, _count in category_counts.most_common(10):
        print(f"\n  {cat} ({_count} methods)")
        print(f"  {'-' * 50}")
        for ex in category_examples[cat]:
            short_class = ex["class"].split("/")[-1]
            print(f"    {short_class}.{ex['method']}{ex['descriptor']}")
            print(f"      -> {ex['diffDescription']}")
    print()

    # ── 4. Total origInsnCount at stake per category ──────────────────
    print("=" * 70)
    print("4. TOTAL ORIGINAL INSTRUCTIONS AT STAKE PER CATEGORY")
    print("=" * 70)
    category_insns = Counter()
    for _, method in all_methods:
        cat = method.get("category", "UNKNOWN")
        category_insns[cat] += method.get("origInsnCount", 0)

    total_insns_at_stake = sum(category_insns.values())
    for cat, insn_sum in category_insns.most_common():
        pct = 100.0 * insn_sum / total_insns_at_stake if total_insns_at_stake else 0
        avg = insn_sum / category_counts[cat] if category_counts[cat] else 0
        print(
            f"  {cat:<30s} {insn_sum:>8d} insns  "
            f"({pct:5.1f}%)  avg {avg:6.1f}/method"
        )
    print(f"  {'TOTAL':<30s} {total_insns_at_stake:>8d} insns")
    print()

    # ── 5. GUARD_CLAUSE_INVERSION: top 5 classes by count ─────────────
    print("=" * 70)
    print("5. GUARD_CLAUSE_INVERSION: TOP 5 CLASSES BY COUNT")
    print("=" * 70)
    guard_class_counts = Counter()
    guard_class_methods = defaultdict(list)
    for class_name, method in all_methods:
        if method.get("category") != "GUARD_CLAUSE_INVERSION":
            continue
        guard_class_counts[class_name] += 1
        if len(guard_class_methods[class_name]) < 5:
            guard_class_methods[class_name].append(
                f"{method['name']}{method.get('descriptor', '')}"
            )

    for class_name, count in guard_class_counts.most_common(5):
        short = class_name.split("/")[-1]
        print(f"\n  {class_name}")
        print(f"    Count: {count}")
        print(f"    Methods:")
        for m in guard_class_methods[class_name]:
            print(f"      - {m}")
    print()


if __name__ == "__main__":
    main()
