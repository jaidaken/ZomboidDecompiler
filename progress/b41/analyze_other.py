#!/usr/bin/env python3
"""Deep analysis of OTHER category mismatches in the verification report."""

import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Optional


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def extract_opcode(context_line: str) -> Optional[str]:
    """Return the opcode token from a context line like '#167  ALOAD v3'."""
    m = re.match(r"#\d+\s+(\S+)", context_line.strip())
    return m.group(1) if m else None


def extract_full_insn(context_line: str) -> str:
    """Return everything after the index prefix (opcode + operands)."""
    m = re.match(r"#\d+\s+(.*)", context_line.strip())
    return m.group(1).strip() if m else context_line.strip()


def find_at_index(context: list[str], idx: int) -> Optional[str]:
    """Find the context line whose #N matches idx."""
    prefix = f"#{idx}"
    for line in context:
        stripped = line.strip()
        if stripped.startswith(prefix):
            # Make sure it's exactly that index (not #16 matching #167)
            after = stripped[len(prefix):]
            if after and after[0] in (' ', '\t'):
                return stripped
    return None


CONSTANT_OPCODES = {
    "ICONST_M1", "ICONST_0", "ICONST_1", "ICONST_2", "ICONST_3",
    "ICONST_4", "ICONST_5", "LCONST_0", "LCONST_1", "FCONST_0",
    "FCONST_1", "FCONST_2", "DCONST_0", "DCONST_1",
    "BIPUSH", "SIPUSH", "LDC",
}

LOAD_OPCODES = {
    "ALOAD", "ILOAD", "LLOAD", "FLOAD", "DLOAD",
}

INVOKE_OPCODES = {
    "INVOKEVIRTUAL", "INVOKEINTERFACE", "INVOKESTATIC", "INVOKESPECIAL",
}

FIELD_OPCODES = {
    "GETFIELD", "PUTFIELD", "GETSTATIC", "PUTSTATIC",
}


def classify_opcode(opcode: str) -> str:
    if opcode in CONSTANT_OPCODES:
        return "constant"
    base = opcode.split("_")[0] if "_" in opcode else opcode
    if base in LOAD_OPCODES:
        return "load"
    if opcode == "GOTO":
        return "goto"
    if opcode in INVOKE_OPCODES:
        return "invoke"
    if opcode in FIELD_OPCODES:
        return "field"
    return "other"


# ---------------------------------------------------------------------------
# Main analysis
# ---------------------------------------------------------------------------

def main():
    report_path = "/home/jaidaken/optizomb/ZomboidDecompiler/progress/b41/report.json"
    print(f"Loading {report_path} ...")
    with open(report_path) as f:
        report = json.load(f)

    # Collect all OTHER methods
    other_methods: list[dict] = []
    class_other_count: Counter = Counter()

    for unit in report["units"]:
        if "methods" not in unit:
            continue
        class_name = unit["name"]
        for m in unit["methods"]:
            if m.get("category") == "OTHER":
                m["_class"] = class_name
                other_methods.append(m)
                class_other_count[class_name] += 1

    print(f"\nTotal OTHER category methods: {len(other_methods)}")
    print("=" * 80)

    # ------------------------------------------------------------------
    # 1. Group by pair of first divergent instructions
    # ------------------------------------------------------------------
    print("\n\n### 1. Grouping by PAIR of first divergent instructions ###\n")

    pair_groups: dict[tuple[str, str], list[dict]] = defaultdict(list)

    for m in other_methods:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)

        orig_insn = extract_full_insn(orig_line) if orig_line else "<missing>"
        recomp_insn = extract_full_insn(recomp_line) if recomp_line else "<missing>"

        pair_groups[(orig_insn, recomp_insn)].append(m)

    # Also group by opcode-pair for a higher-level view
    opcode_pair_groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for m in other_methods:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)

        orig_op = extract_opcode(orig_line) if orig_line else "<missing>"
        recomp_op = extract_opcode(recomp_line) if recomp_line else "<missing>"

        opcode_pair_groups[(orig_op, recomp_op)].append(m)

    print("--- By exact instruction pair (top 30) ---")
    for (orig, recomp), methods in sorted(pair_groups.items(), key=lambda x: -len(x[1]))[:30]:
        examples = [f"{m['_class']}.{m['name']}" for m in methods[:2]]
        print(f"  [{len(methods):4d}] ORIG: {orig}")
        print(f"         RECOMP: {recomp}")
        print(f"         Examples: {', '.join(examples)}")
        print()

    print("\n--- By opcode pair (top 30) ---")
    for (orig_op, recomp_op), methods in sorted(opcode_pair_groups.items(), key=lambda x: -len(x[1]))[:30]:
        examples = [f"{m['_class']}.{m['name']}" for m in methods[:2]]
        print(f"  [{len(methods):4d}] ORIG_OP: {orig_op:20s} | RECOMP_OP: {recomp_op}")
        print(f"         Examples: {', '.join(examples)}")

    # ------------------------------------------------------------------
    # 2. (already shown above with examples)
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # 3. Instruction count diff distribution
    # ------------------------------------------------------------------
    print("\n\n### 3. Instruction count diff (origInsnCount - recompInsnCount) ###\n")

    diffs = [m["origInsnCount"] - m["recompInsnCount"] for m in other_methods]
    diff_counter = Counter(diffs)

    print(f"  Min diff: {min(diffs)}")
    print(f"  Max diff: {max(diffs)}")
    print(f"  Mean diff: {sum(diffs) / len(diffs):.2f}")
    print(f"  Median diff: {sorted(diffs)[len(diffs)//2]}")
    print(f"  Std dev: {(sum((d - sum(diffs)/len(diffs))**2 for d in diffs) / len(diffs)) ** 0.5:.2f}")
    print()

    # Bucket distribution
    buckets = defaultdict(int)
    for d in diffs:
        if d == 0:
            buckets["  0 (same size)"] += 1
        elif -5 <= d < 0:
            buckets[" -5 to -1"] += 1
        elif -20 <= d < -5:
            buckets["-20 to -6"] += 1
        elif d < -20:
            buckets["< -20"] += 1
        elif 1 <= d <= 5:
            buckets["  1 to  5"] += 1
        elif 6 <= d <= 20:
            buckets["  6 to 20"] += 1
        else:
            buckets["> 20"] += 1

    print("  Distribution by bucket:")
    for bucket in sorted(buckets.keys()):
        print(f"    {bucket}: {buckets[bucket]:4d}  ({100*buckets[bucket]/len(diffs):.1f}%)")

    print("\n  Top 20 most common exact diffs:")
    for diff_val, count in diff_counter.most_common(20):
        print(f"    diff={diff_val:+5d}: {count:4d} methods")

    # ------------------------------------------------------------------
    # 4. Methods where origInsnCount == recompInsnCount
    # ------------------------------------------------------------------
    print("\n\n### 4. Same-size methods (origInsnCount == recompInsnCount) ###\n")

    same_size = [m for m in other_methods if m["origInsnCount"] == m["recompInsnCount"]]
    print(f"  Count: {len(same_size)} / {len(other_methods)} ({100*len(same_size)/len(other_methods):.1f}%)")

    # Analyze opcode pairs for same-size methods
    same_opcode_pairs: Counter = Counter()
    for m in same_size:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        orig_op = extract_opcode(orig_line) if orig_line else "<missing>"
        recomp_op = extract_opcode(recomp_line) if recomp_line else "<missing>"
        same_opcode_pairs[(orig_op, recomp_op)] += 1

    print("\n  Opcode pair patterns in same-size methods (top 20):")
    for (orig_op, recomp_op), count in same_opcode_pairs.most_common(20):
        # Find an example
        ex = None
        for m in same_size:
            idx = m["firstDiffIndex"]
            orig_line = find_at_index(m.get("origContext", []), idx)
            recomp_line = find_at_index(m.get("recompContext", []), idx)
            o = extract_opcode(orig_line) if orig_line else "<missing>"
            r = extract_opcode(recomp_line) if recomp_line else "<missing>"
            if o == orig_op and r == recomp_op:
                ex = m
                break
        ex_name = f"{ex['_class']}.{ex['name']}" if ex else "?"
        print(f"    [{count:4d}] {orig_op:20s} vs {recomp_op:20s}  e.g. {ex_name}")

    # Check if same-size + same opcode = truly just operand differences
    same_size_same_op = [m for m in same_size
                         if (extract_opcode(find_at_index(m.get("origContext", []), m["firstDiffIndex"])) or "") ==
                            (extract_opcode(find_at_index(m.get("recompContext", []), m["firstDiffIndex"])) or "")]
    print(f"\n  Same size AND same first-diff opcode: {len(same_size_same_op)} "
          f"({100*len(same_size_same_op)/max(len(same_size),1):.1f}% of same-size)")

    # ------------------------------------------------------------------
    # 5. Sub-pattern detection
    # ------------------------------------------------------------------
    print("\n\n### 5. Sub-pattern detection ###\n")

    # 5a. Variable reordering (same LOAD opcode, different variable)
    var_reorder = []
    for m in other_methods:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        if not orig_line or not recomp_line:
            continue
        orig_op = extract_opcode(orig_line)
        recomp_op = extract_opcode(recomp_line)
        if orig_op and recomp_op and orig_op == recomp_op:
            base_orig = orig_op.split("_")[0] if "_" in orig_op else orig_op
            if base_orig in LOAD_OPCODES:
                orig_insn = extract_full_insn(orig_line)
                recomp_insn = extract_full_insn(recomp_line)
                if orig_insn != recomp_insn:
                    var_reorder.append(m)

    print(f"  5a. Variable reordering (same LOAD opcode, different variable): {len(var_reorder)}")
    for m in var_reorder[:5]:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        print(f"      {m['_class']}.{m['name']}")
        print(f"        ORIG:   {extract_full_insn(orig_line)}")
        print(f"        RECOMP: {extract_full_insn(recomp_line)}")

    # 5b. GOTO asymmetry (one side has GOTO, other doesn't)
    goto_asym = []
    for m in other_methods:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        if not orig_line or not recomp_line:
            continue
        orig_op = extract_opcode(orig_line)
        recomp_op = extract_opcode(recomp_line)
        if (orig_op == "GOTO") != (recomp_op == "GOTO"):
            goto_asym.append(m)

    print(f"\n  5b. GOTO asymmetry (GOTO on one side only): {len(goto_asym)}")
    # Sub-categorize
    goto_orig_only = [m for m in goto_asym
                      if extract_opcode(find_at_index(m.get("origContext", []), m["firstDiffIndex"])) == "GOTO"]
    goto_recomp_only = [m for m in goto_asym
                        if extract_opcode(find_at_index(m.get("recompContext", []), m["firstDiffIndex"])) == "GOTO"]
    print(f"      GOTO in orig only:   {len(goto_orig_only)}")
    print(f"      GOTO in recomp only: {len(goto_recomp_only)}")
    for m in goto_asym[:5]:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        print(f"      {m['_class']}.{m['name']}")
        print(f"        ORIG:   {extract_full_insn(orig_line)}")
        print(f"        RECOMP: {extract_full_insn(recomp_line)}")

    # 5c. Different constants
    diff_constants = []
    for m in other_methods:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        if not orig_line or not recomp_line:
            continue
        orig_op = extract_opcode(orig_line)
        recomp_op = extract_opcode(recomp_line)
        orig_cls = classify_opcode(orig_op) if orig_op else ""
        recomp_cls = classify_opcode(recomp_op) if recomp_op else ""
        if orig_cls == "constant" and recomp_cls == "constant":
            orig_insn = extract_full_insn(orig_line)
            recomp_insn = extract_full_insn(recomp_line)
            if orig_insn != recomp_insn:
                diff_constants.append(m)

    print(f"\n  5c. Different constants (both sides are constant ops): {len(diff_constants)}")
    for m in diff_constants[:5]:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        print(f"      {m['_class']}.{m['name']}")
        print(f"        ORIG:   {extract_full_insn(orig_line)}")
        print(f"        RECOMP: {extract_full_insn(recomp_line)}")

    # Also: one side constant, other side not
    const_vs_other = []
    for m in other_methods:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        if not orig_line or not recomp_line:
            continue
        orig_op = extract_opcode(orig_line)
        recomp_op = extract_opcode(recomp_line)
        orig_cls = classify_opcode(orig_op) if orig_op else ""
        recomp_cls = classify_opcode(recomp_op) if recomp_op else ""
        if (orig_cls == "constant") != (recomp_cls == "constant"):
            const_vs_other.append(m)
    print(f"      Constant on one side only: {len(const_vs_other)}")

    # 5d. Same opcode, different operands (field/method references)
    same_op_diff_operand = []
    for m in other_methods:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        if not orig_line or not recomp_line:
            continue
        orig_op = extract_opcode(orig_line)
        recomp_op = extract_opcode(recomp_line)
        if orig_op == recomp_op:
            orig_insn = extract_full_insn(orig_line)
            recomp_insn = extract_full_insn(recomp_line)
            if orig_insn != recomp_insn:
                same_op_diff_operand.append(m)

    print(f"\n  5d. Same opcode, different operands: {len(same_op_diff_operand)}")

    # Break down by opcode
    same_op_by_opcode: Counter = Counter()
    for m in same_op_diff_operand:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        orig_op = extract_opcode(orig_line) if orig_line else "?"
        same_op_by_opcode[orig_op] += 1

    print("      Breakdown by opcode:")
    for op, count in same_op_by_opcode.most_common(15):
        # Find example
        ex = None
        for m in same_op_diff_operand:
            idx = m["firstDiffIndex"]
            orig_line = find_at_index(m.get("origContext", []), idx)
            if extract_opcode(orig_line) == op:
                ex = m
                break
        if ex:
            idx = ex["firstDiffIndex"]
            ol = find_at_index(ex.get("origContext", []), idx)
            rl = find_at_index(ex.get("recompContext", []), idx)
            print(f"        {op:22s}: {count:4d}")
            print(f"          e.g. {ex['_class']}.{ex['name']}")
            print(f"            ORIG:   {extract_full_insn(ol)}")
            print(f"            RECOMP: {extract_full_insn(rl)}")

    # Specifically for field/method reference differences
    field_method_ref_diff = []
    for m in same_op_diff_operand:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        orig_op = extract_opcode(orig_line) if orig_line else ""
        if orig_op in INVOKE_OPCODES | FIELD_OPCODES:
            field_method_ref_diff.append(m)

    print(f"\n      Field/method reference differences specifically: {len(field_method_ref_diff)}")
    for m in field_method_ref_diff[:5]:
        idx = m["firstDiffIndex"]
        ol = find_at_index(m.get("origContext", []), idx)
        rl = find_at_index(m.get("recompContext", []), idx)
        print(f"        {m['_class']}.{m['name']}")
        print(f"          ORIG:   {extract_full_insn(ol)}")
        print(f"          RECOMP: {extract_full_insn(rl)}")

    # ------------------------------------------------------------------
    # 5-extra: Cross-cutting: how do the sub-patterns overlap?
    # ------------------------------------------------------------------
    print("\n\n### 5-extra. Sub-pattern overlap summary ###\n")

    # Tag each method
    all_tags = defaultdict(set)
    for m in var_reorder:
        all_tags[id(m)].add("var_reorder")
    for m in goto_asym:
        all_tags[id(m)].add("goto_asym")
    for m in diff_constants:
        all_tags[id(m)].add("diff_const")
    for m in const_vs_other:
        all_tags[id(m)].add("const_vs_other")
    for m in same_op_diff_operand:
        all_tags[id(m)].add("same_op_diff_operand")

    tagged = len(all_tags)
    untagged = len(other_methods) - tagged
    print(f"  Methods matching at least one sub-pattern: {tagged}")
    print(f"  Methods matching NO sub-pattern: {untagged} ({100*untagged/len(other_methods):.1f}%)")

    # What are the untagged methods?
    tagged_ids = set(all_tags.keys())
    untagged_methods = [m for m in other_methods if id(m) not in tagged_ids]
    untagged_opcode_pairs: Counter = Counter()
    for m in untagged_methods:
        idx = m["firstDiffIndex"]
        orig_line = find_at_index(m.get("origContext", []), idx)
        recomp_line = find_at_index(m.get("recompContext", []), idx)
        orig_op = extract_opcode(orig_line) if orig_line else "<missing>"
        recomp_op = extract_opcode(recomp_line) if recomp_line else "<missing>"
        untagged_opcode_pairs[(orig_op, recomp_op)] += 1

    print("\n  Untagged methods - opcode pairs (top 15):")
    for (orig_op, recomp_op), count in untagged_opcode_pairs.most_common(15):
        ex = None
        for m in untagged_methods:
            idx = m["firstDiffIndex"]
            orig_line = find_at_index(m.get("origContext", []), idx)
            recomp_line = find_at_index(m.get("recompContext", []), idx)
            o = extract_opcode(orig_line) if orig_line else "<missing>"
            r = extract_opcode(recomp_line) if recomp_line else "<missing>"
            if o == orig_op and r == recomp_op:
                ex = m
                break
        ex_name = f"{ex['_class']}.{ex['name']}" if ex else "?"
        print(f"    [{count:4d}] {orig_op:20s} vs {recomp_op:20s}  e.g. {ex_name}")

    # ------------------------------------------------------------------
    # 6. Top 10 classes by OTHER mismatch count
    # ------------------------------------------------------------------
    print("\n\n### 6. Top 10 classes by OTHER mismatch count ###\n")

    for class_name, count in class_other_count.most_common(10):
        # Gather method names
        class_methods = [m for m in other_methods if m["_class"] == class_name]
        method_names = [m["name"] for m in class_methods]
        print(f"  [{count:3d}] {class_name}")
        # Show up to 10 method names
        for mn in method_names[:10]:
            mc = [m for m in class_methods if m["name"] == mn][0]
            diff = mc["origInsnCount"] - mc["recompInsnCount"]
            print(f"         - {mn}{mc['descriptor']}  (diff={diff:+d}, firstDiff=#{mc['firstDiffIndex']})")
        if len(method_names) > 10:
            print(f"         ... and {len(method_names) - 10} more")

    # ------------------------------------------------------------------
    # 7. Summary statistics
    # ------------------------------------------------------------------
    print("\n\n### Summary ###\n")
    print(f"  Total OTHER mismatches:                {len(other_methods)}")
    print(f"  Same-size methods:                     {len(same_size)} ({100*len(same_size)/len(other_methods):.1f}%)")
    print(f"  Variable reordering:                   {len(var_reorder)}")
    print(f"  GOTO asymmetry:                        {len(goto_asym)}")
    print(f"  Different constants (both sides):       {len(diff_constants)}")
    print(f"  Constant vs non-constant:              {len(const_vs_other)}")
    print(f"  Same opcode, different operands:        {len(same_op_diff_operand)}")
    print(f"    - Field/method ref differences:       {len(field_method_ref_diff)}")
    print(f"  Methods with no sub-pattern matched:    {untagged}")
    print(f"  Classes with OTHER mismatches:          {len(class_other_count)}")


if __name__ == "__main__":
    main()
