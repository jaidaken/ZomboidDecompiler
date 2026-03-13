#!/usr/bin/env python3
"""
Analyze GUARD_CLAUSE_INVERSION mismatches from the b41 verification report.
Extracts representative samples, displays context, and identifies common patterns.
"""

import json
import re
from collections import Counter, defaultdict

REPORT_PATH = "/home/jaidaken/optizomb/ZomboidDecompiler/progress/b41/report.json"

def load_guard_mismatches():
    with open(REPORT_PATH) as f:
        data = json.load(f)

    mismatches = []
    for unit in data["units"]:
        for m in unit.get("methods", []):
            if m.get("category") == "GUARD_CLAUSE_INVERSION":
                mismatches.append({
                    "class": unit["name"],
                    "method": m["name"],
                    "desc": m.get("descriptor", ""),
                    "origSize": m.get("origInsnCount", 0),
                    "recompSize": m.get("recompInsnCount", 0),
                    "origContext": m.get("origContext", []),
                    "recompContext": m.get("recompContext", []),
                    "divergeIndex": m.get("firstDiffIndex", -1),
                    "diffDescription": m.get("diffDescription", ""),
                })
    return mismatches


def parse_insn(line):
    """Parse '#NNN  OPCODE ...' format, return (index, opcode, full_operands)"""
    m = re.match(r"#(\d+)\s+(\S+)(.*)", line.strip())
    if m:
        return int(m.group(1)), m.group(2), m.group(3).strip()
    return None, None, None


def extract_divergence_opcodes(orig_ctx, recomp_ctx, diverge_idx):
    """Extract the opcodes at and around the divergence point."""
    orig_at_div = None
    recomp_at_div = None
    for line in orig_ctx:
        idx, opcode, operands = parse_insn(line)
        if idx == diverge_idx:
            orig_at_div = (opcode, operands)
            break
    for line in recomp_ctx:
        idx, opcode, operands = parse_insn(line)
        if idx == diverge_idx:
            recomp_at_div = (opcode, operands)
            break
    return orig_at_div, recomp_at_div


def classify_divergence(orig_at, recomp_at):
    """Classify the nature of the divergence."""
    if orig_at is None or recomp_at is None:
        return "CONTEXT_MISSING"

    orig_op, orig_operands = orig_at
    recomp_op, recomp_operands = recomp_at

    # Check for inverse condition pairs
    inverse_pairs = {
        "IFEQ": "IFNE", "IFNE": "IFEQ",
        "IFLT": "IFGE", "IFGE": "IFLT",
        "IFGT": "IFLE", "IFLE": "IFGT",
        "IFNULL": "IFNONNULL", "IFNONNULL": "IFNULL",
        "IF_ICMPEQ": "IF_ICMPNE", "IF_ICMPNE": "IF_ICMPEQ",
        "IF_ICMPLT": "IF_ICMPGE", "IF_ICMPGE": "IF_ICMPLT",
        "IF_ICMPGT": "IF_ICMPLE", "IF_ICMPLE": "IF_ICMPGT",
        "IF_ACMPEQ": "IF_ACMPNE", "IF_ACMPNE": "IF_ACMPEQ",
    }

    if orig_op in inverse_pairs and inverse_pairs[orig_op] == recomp_op:
        return f"CONDITION_INVERSE ({orig_op} -> {recomp_op})"

    if orig_op == recomp_op and orig_operands != recomp_operands:
        return f"SAME_OP_DIFF_TARGET ({orig_op})"

    if orig_op != recomp_op:
        return f"DIFFERENT_OPS ({orig_op} vs {recomp_op})"

    return f"SAME ({orig_op})"


def analyze_context_structure(ctx, diverge_idx):
    """Analyze what comes after the divergence in a context."""
    insns = []
    found_div = False
    for line in ctx:
        idx, opcode, operands = parse_insn(line)
        if idx is not None:
            if idx >= diverge_idx:
                found_div = True
            if found_div:
                insns.append((idx, opcode, operands))

    # Count returns, gotos, conditions after divergence
    returns = sum(1 for _, op, _ in insns if op in ("RETURN", "IRETURN", "ARETURN", "LRETURN", "FRETURN", "DRETURN"))
    gotos = sum(1 for _, op, _ in insns if op == "GOTO")
    conditions = sum(1 for _, op, _ in insns if op.startswith("IF"))
    athrows = sum(1 for _, op, _ in insns if op == "ATHROW")

    return {
        "total_after_div": len(insns),
        "returns": returns,
        "gotos": gotos,
        "conditions": conditions,
        "athrows": athrows,
    }


def pick_representative_samples(mismatches, n=10):
    """Pick n representative samples from different classes with varying sizes."""
    # Sort by class to spread across different areas
    classes_seen = set()
    by_size = sorted(mismatches, key=lambda m: m["origSize"])

    # Pick from different size buckets and different classes
    buckets = [
        ("tiny (1-20)", [m for m in by_size if m["origSize"] <= 20]),
        ("small (21-50)", [m for m in by_size if 20 < m["origSize"] <= 50]),
        ("medium (51-150)", [m for m in by_size if 50 < m["origSize"] <= 150]),
        ("large (151-300)", [m for m in by_size if 150 < m["origSize"] <= 300]),
        ("xlarge (300+)", [m for m in by_size if m["origSize"] > 300]),
    ]

    selected = []
    for label, bucket in buckets:
        count = 0
        for m in bucket:
            if m["class"] not in classes_seen and count < 2:
                selected.append((label, m))
                classes_seen.add(m["class"])
                count += 1
            if count >= 2:
                break

    return selected


def count_inversions_in_context(orig_ctx, recomp_ctx):
    """Count how many instructions differ between the two contexts (rough)."""
    orig_ops = []
    recomp_ops = []
    for line in orig_ctx:
        _, op, operands = parse_insn(line)
        if op:
            orig_ops.append((op, operands))
    for line in recomp_ctx:
        _, op, operands = parse_insn(line)
        if op:
            recomp_ops.append((op, operands))

    diffs = 0
    for i in range(min(len(orig_ops), len(recomp_ops))):
        if orig_ops[i] != recomp_ops[i]:
            diffs += 1
    diffs += abs(len(orig_ops) - len(recomp_ops))
    return diffs, len(orig_ops), len(recomp_ops)


def detect_guard_pattern(orig_ctx, recomp_ctx, diverge_idx):
    """Try to detect what specific guard clause pattern this is."""
    # Get instruction lists after divergence
    orig_insns = []
    recomp_insns = []
    for line in orig_ctx:
        idx, op, operands = parse_insn(line)
        if idx is not None and idx >= diverge_idx:
            orig_insns.append((op, operands))
    for line in recomp_ctx:
        idx, op, operands = parse_insn(line)
        if idx is not None and idx >= diverge_idx:
            recomp_insns.append((op, operands))

    if not orig_insns or not recomp_insns:
        return "INSUFFICIENT_CONTEXT"

    orig_first = orig_insns[0][0]
    recomp_first = recomp_insns[0][0]

    # Both start with conditional branches (typical inversion)
    if orig_first.startswith("IF") and recomp_first.startswith("IF"):
        # Check for RETURN within first few instructions after the condition
        orig_has_early_return = any(
            op in ("RETURN", "IRETURN", "ARETURN", "LRETURN", "FRETURN", "DRETURN", "ATHROW")
            for op, _ in orig_insns[1:6]
        )
        recomp_has_early_return = any(
            op in ("RETURN", "IRETURN", "ARETURN", "LRETURN", "FRETURN", "DRETURN", "ATHROW")
            for op, _ in recomp_insns[1:6]
        )

        if orig_has_early_return != recomp_has_early_return:
            return "GUARD_BODY_POSITION_SWAP"
        elif orig_has_early_return and recomp_has_early_return:
            return "BOTH_HAVE_EARLY_RETURN"
        else:
            return "CONDITION_INVERSE_NO_EARLY_RETURN"

    # One starts with conditional, other with something else
    if orig_first.startswith("IF") or recomp_first.startswith("IF"):
        return "ASYMMETRIC_CONDITION"

    return f"NON_CONDITIONAL ({orig_first} vs {recomp_first})"


def check_same_size(mismatches):
    """Check how many have same instruction count on both sides."""
    same = sum(1 for m in mismatches if m["origSize"] == m["recompSize"])
    diff = len(mismatches) - same
    return same, diff


def main():
    mismatches = load_guard_mismatches()
    print(f"=" * 100)
    print(f"GUARD_CLAUSE_INVERSION Analysis: {len(mismatches)} cases")
    print(f"=" * 100)

    # ─── Section 1: Overview Statistics ───
    print(f"\n{'─' * 80}")
    print("SECTION 1: OVERVIEW STATISTICS")
    print(f"{'─' * 80}")

    sizes = [m["origSize"] for m in mismatches]
    print(f"  Method sizes: min={min(sizes)}, max={max(sizes)}, median={sorted(sizes)[len(sizes)//2]}")

    same_size, diff_size = check_same_size(mismatches)
    print(f"  Same instruction count (orig == recomp): {same_size} ({100*same_size/len(mismatches):.1f}%)")
    print(f"  Different instruction count:              {diff_size} ({100*diff_size/len(mismatches):.1f}%)")

    size_diffs = [abs(m["origSize"] - m["recompSize"]) for m in mismatches if m["origSize"] != m["recompSize"]]
    if size_diffs:
        print(f"  When different: avg delta={sum(size_diffs)/len(size_diffs):.1f}, max delta={max(size_diffs)}")

    # Unique classes
    classes = set(m["class"] for m in mismatches)
    print(f"  Unique classes: {len(classes)}")

    # Methods per class
    methods_per_class = Counter(m["class"] for m in mismatches)
    top_classes = methods_per_class.most_common(10)
    print(f"\n  Top 10 classes by GUARD_CLAUSE_INVERSION count:")
    for cls, cnt in top_classes:
        print(f"    {cls}: {cnt}")

    # ─── Section 2: Divergence Analysis ───
    print(f"\n{'─' * 80}")
    print("SECTION 2: DIVERGENCE POINT ANALYSIS")
    print(f"{'─' * 80}")

    div_classifications = Counter()
    div_op_pairs = Counter()

    for m in mismatches:
        orig_at, recomp_at = extract_divergence_opcodes(m["origContext"], m["recompContext"], m["divergeIndex"])
        classification = classify_divergence(orig_at, recomp_at)
        div_classifications[classification] += 1
        if orig_at and recomp_at:
            div_op_pairs[f"{orig_at[0]} -> {recomp_at[0]}"] += 1

    print("\n  Divergence classifications:")
    for cls, cnt in div_classifications.most_common():
        print(f"    {cls}: {cnt} ({100*cnt/len(mismatches):.1f}%)")

    print("\n  Top 15 opcode pairs at divergence point:")
    for pair, cnt in div_op_pairs.most_common(15):
        print(f"    {pair}: {cnt}")

    # ─── Section 3: Guard Pattern Detection ───
    print(f"\n{'─' * 80}")
    print("SECTION 3: GUARD PATTERN DETECTION")
    print(f"{'─' * 80}")

    patterns = Counter()
    for m in mismatches:
        pat = detect_guard_pattern(m["origContext"], m["recompContext"], m["divergeIndex"])
        patterns[pat] += 1

    print("\n  Guard patterns detected:")
    for pat, cnt in patterns.most_common():
        print(f"    {pat}: {cnt} ({100*cnt/len(mismatches):.1f}%)")

    # ─── Section 4: Context Structure Analysis ───
    print(f"\n{'─' * 80}")
    print("SECTION 4: POST-DIVERGENCE STRUCTURE")
    print(f"{'─' * 80}")

    # Analyze how many returns/gotos/conditions appear after divergence
    orig_returns = []
    recomp_returns = []
    for m in mismatches:
        orig_struct = analyze_context_structure(m["origContext"], m["divergeIndex"])
        recomp_struct = analyze_context_structure(m["recompContext"], m["divergeIndex"])
        orig_returns.append(orig_struct["returns"])
        recomp_returns.append(recomp_struct["returns"])

    print(f"\n  Returns in context after divergence:")
    print(f"    Orig:   avg={sum(orig_returns)/len(orig_returns):.1f}, max={max(orig_returns)}")
    print(f"    Recomp: avg={sum(recomp_returns)/len(recomp_returns):.1f}, max={max(recomp_returns)}")

    # Check: how many have GOTO right after the guard body?
    goto_after_guard = 0
    for m in mismatches:
        for ctx in [m["origContext"], m["recompContext"]]:
            for i, line in enumerate(ctx):
                idx, op, _ = parse_insn(line)
                if idx is not None and idx == m["divergeIndex"]:
                    # Look for a return within 6 insns, then GOTO
                    for j in range(i+1, min(i+7, len(ctx))):
                        _, op2, _ = parse_insn(ctx[j])
                        if op2 in ("RETURN", "IRETURN", "ARETURN", "LRETURN", "FRETURN", "DRETURN"):
                            goto_after_guard += 1
                            break
                    break
    print(f"\n  Contexts with RETURN within 6 insns of divergence: {goto_after_guard} (out of {len(mismatches)*2} contexts)")

    # ─── Section 5: Detailed instruction-level pattern matching ───
    print(f"\n{'─' * 80}")
    print("SECTION 5: INSTRUCTION PATTERN AT DIVERGENCE (first 8 opcodes after divergence)")
    print(f"{'─' * 80}")

    orig_patterns = Counter()
    recomp_patterns = Counter()
    combined_patterns = Counter()

    for m in mismatches:
        def get_opcodes_after_div(ctx, div_idx, count=8):
            ops = []
            found = False
            for line in ctx:
                idx, op, _ = parse_insn(line)
                if idx is not None and idx >= div_idx:
                    found = True
                if found and op:
                    ops.append(op)
                    if len(ops) >= count:
                        break
            return tuple(ops)

        orig_ops = get_opcodes_after_div(m["origContext"], m["divergeIndex"])
        recomp_ops = get_opcodes_after_div(m["recompContext"], m["divergeIndex"])

        # Generalize: replace specific IF variants with "IF_xxx"
        def generalize(ops):
            result = []
            for op in ops:
                if op.startswith("IF"):
                    result.append("IF_xxx")
                elif op in ("RETURN", "IRETURN", "ARETURN", "LRETURN", "FRETURN", "DRETURN"):
                    result.append("xRETURN")
                elif op.startswith("ALOAD") or op.startswith("ILOAD") or op.startswith("FLOAD") or op.startswith("DLOAD") or op.startswith("LLOAD"):
                    result.append("xLOAD")
                elif op.startswith("ASTORE") or op.startswith("ISTORE") or op.startswith("FSTORE") or op.startswith("DSTORE") or op.startswith("LSTORE"):
                    result.append("xSTORE")
                elif op.startswith("ICONST") or op.startswith("BIPUSH") or op.startswith("SIPUSH") or op == "LDC":
                    result.append("CONST")
                elif op.startswith("GETFIELD") or op.startswith("GETSTATIC"):
                    result.append("GET_x")
                elif op.startswith("INVOKE"):
                    result.append("INVOKE_x")
                elif op == "GOTO":
                    result.append("GOTO")
                elif op == "ATHROW":
                    result.append("ATHROW")
                else:
                    result.append(op)
            return tuple(result)

        orig_gen = generalize(orig_ops)
        recomp_gen = generalize(recomp_ops)
        combined_patterns[(orig_gen, recomp_gen)] += 1

    print("\n  Top 20 generalized (orig_pattern, recomp_pattern) pairs:")
    for (orig_p, recomp_p), cnt in combined_patterns.most_common(20):
        pct = 100 * cnt / len(mismatches)
        print(f"    [{cnt:3d}] ({pct:4.1f}%) orig={' '.join(orig_p)}")
        print(f"           {'':>8} recomp={' '.join(recomp_p)}")

    # ─── Section 6: Representative Samples ───
    print(f"\n{'─' * 80}")
    print("SECTION 6: 10 REPRESENTATIVE SAMPLES")
    print(f"{'─' * 80}")

    samples = pick_representative_samples(mismatches, 10)

    for i, (bucket, m) in enumerate(samples):
        print(f"\n{'━' * 80}")
        print(f"  SAMPLE {i+1} [{bucket}]: {m['class']}#{m['method']}{m['desc']}")
        print(f"  Sizes: orig={m['origSize']}, recomp={m['recompSize']}, diverge@#{m['divergeIndex']}")
        print(f"  {m['diffDescription']}")

        diffs, n_orig, n_recomp = count_inversions_in_context(m["origContext"], m["recompContext"])
        print(f"  Context diffs: {diffs} differing instructions out of {max(n_orig, n_recomp)} in context")

        orig_at, recomp_at = extract_divergence_opcodes(m["origContext"], m["recompContext"], m["divergeIndex"])
        classification = classify_divergence(orig_at, recomp_at)
        print(f"  Classification: {classification}")

        pattern = detect_guard_pattern(m["origContext"], m["recompContext"], m["divergeIndex"])
        print(f"  Guard pattern: {pattern}")

        print(f"\n  ORIG (first 15 lines):")
        for line in m["origContext"][:15]:
            print(f"    {line}")

        print(f"\n  RECOMP (first 15 lines):")
        for line in m["recompContext"][:15]:
            print(f"    {line}")

    # ─── Section 7: Deep-dive into same-size vs different-size ───
    print(f"\n{'─' * 80}")
    print("SECTION 7: SAME-SIZE vs DIFFERENT-SIZE BREAKDOWN")
    print(f"{'─' * 80}")

    same_size_cases = [m for m in mismatches if m["origSize"] == m["recompSize"]]
    diff_size_cases = [m for m in mismatches if m["origSize"] != m["recompSize"]]

    print(f"\n  Same-size ({len(same_size_cases)} cases):")
    ss_patterns = Counter()
    for m in same_size_cases:
        pat = detect_guard_pattern(m["origContext"], m["recompContext"], m["divergeIndex"])
        ss_patterns[pat] += 1
    for pat, cnt in ss_patterns.most_common():
        print(f"    {pat}: {cnt}")

    print(f"\n  Different-size ({len(diff_size_cases)} cases):")
    ds_patterns = Counter()
    ds_deltas = Counter()
    for m in diff_size_cases:
        pat = detect_guard_pattern(m["origContext"], m["recompContext"], m["divergeIndex"])
        ds_patterns[pat] += 1
        delta = abs(m["origSize"] - m["recompSize"])
        ds_deltas[delta] += 1
    for pat, cnt in ds_patterns.most_common():
        print(f"    {pat}: {cnt}")
    print(f"\n  Size deltas distribution:")
    for delta, cnt in sorted(ds_deltas.items()):
        print(f"    delta={delta}: {cnt} cases")

    # ─── Section 8: Specific condition-pair analysis ───
    print(f"\n{'─' * 80}")
    print("SECTION 8: WHAT MAKES THESE ESCAPE CURRENT NORMALIZATION?")
    print(f"{'─' * 80}")

    # Check: how many have the guard (return-terminated block) > 20 instructions?
    long_guards = 0
    multi_condition_guards = 0
    has_goto_in_guard = 0
    has_switch_in_guard = 0
    has_invoke_in_guard = 0

    for m in mismatches:
        # Look at both contexts to find the guard body
        for ctx in [m["origContext"], m["recompContext"]]:
            insns_after = []
            found = False
            for line in ctx:
                idx, op, operands = parse_insn(line)
                if idx is not None and idx == m["divergeIndex"]:
                    found = True
                    continue
                if found and op:
                    insns_after.append((op, operands))

            # Find first return/throw to delimit guard body
            guard_len = 0
            has_inner_cond = False
            has_inner_goto = False
            has_inner_switch = False
            has_inner_invoke = False
            for op, operands in insns_after:
                if op in ("RETURN", "IRETURN", "ARETURN", "LRETURN", "FRETURN", "DRETURN", "ATHROW"):
                    guard_len += 1
                    break
                guard_len += 1
                if op.startswith("IF"):
                    has_inner_cond = True
                if op == "GOTO":
                    has_inner_goto = True
                if op.startswith("SWITCH") or op.startswith("TABLESWITCH") or op.startswith("LOOKUPSWITCH"):
                    has_inner_switch = True
                if op.startswith("INVOKE"):
                    has_inner_invoke = True

            if guard_len > 20:
                long_guards += 1
                break
            if has_inner_cond:
                multi_condition_guards += 1
                break
            if has_inner_goto:
                has_goto_in_guard += 1
                break
            if has_inner_switch:
                has_switch_in_guard += 1
                break

    print(f"""
  Why do these 205 cases escape current normalization?

  The BytecodeComparator has these guard-clause mechanisms:
  1. normalizeGuardClauses(): IF_xxx; RETURN -> IF_inverted (void only)
  2. canonicalizeIfElse(): GOTO-based if/else block swapping (non-canonical conditions)
  3. tryMatchGuardInversion(): comparison-level guard swap detection (short guards 1-20 insns)
  4. tryGuardElimination(): strips guard blocks when one side eliminated the guard
  5. tryBlockLevelMatch(): multiset block matching for multiple inversions
  6. tryReturnTerminatedInversion(): splits at returns, tries swapping halves

  Potential reasons these cases escape:

  Guard body characteristics (from context analysis):
  - Guards longer than 20 instructions:     {long_guards}
  - Guards with inner conditional branches:  {multi_condition_guards}
  - Guards with GOTO (complex control flow): {has_goto_in_guard}
  - Guards with SWITCH:                      {has_switch_in_guard}

  Same-size cases: {len(same_size_cases)} (pure inversion, no elimination)
  Different-size cases: {len(diff_size_cases)} (guard elimination/addition)
""")

    # Check how many diverge at a non-conditional instruction
    non_cond_div = 0
    for m in mismatches:
        orig_at, recomp_at = extract_divergence_opcodes(m["origContext"], m["recompContext"], m["divergeIndex"])
        if orig_at and not orig_at[0].startswith("IF"):
            non_cond_div += 1
        elif recomp_at and not recomp_at[0].startswith("IF"):
            non_cond_div += 1

    print(f"  Divergence at non-conditional instruction: {non_cond_div}")
    print(f"  (These may involve label-only differences before the actual guard)")

    # Check: how many have multiple divergence points visible in context?
    multi_div = 0
    for m in mismatches:
        orig_ops = []
        recomp_ops = []
        for line in m["origContext"]:
            _, op, operands = parse_insn(line)
            if op:
                orig_ops.append(f"{op} {operands}")
        for line in m["recompContext"]:
            _, op, operands = parse_insn(line)
            if op:
                recomp_ops.append(f"{op} {operands}")

        diff_count = 0
        for j in range(min(len(orig_ops), len(recomp_ops))):
            if orig_ops[j] != recomp_ops[j]:
                diff_count += 1
        if diff_count > 3:
            multi_div += 1

    print(f"  Cases with >3 differing instructions in context: {multi_div}")
    print(f"  (Indicates multiple inversions or significant structural differences)")

    # ─── Section 9: Specific examples of tricky patterns ───
    print(f"\n{'─' * 80}")
    print("SECTION 9: TRICKY PATTERN EXAMPLES")
    print(f"{'─' * 80}")

    # Find cases where divergence is NOT at a conditional branch
    print("\n  Cases where divergence is at a non-conditional instruction:")
    shown = 0
    for m in mismatches:
        orig_at, recomp_at = extract_divergence_opcodes(m["origContext"], m["recompContext"], m["divergeIndex"])
        if orig_at and not orig_at[0].startswith("IF") and recomp_at and not recomp_at[0].startswith("IF"):
            print(f"    {m['class']}#{m['method']} @ #{m['divergeIndex']}: {orig_at[0]} vs {recomp_at[0]}")
            shown += 1
            if shown >= 5:
                break

    # Find cases with largest size differences
    print("\n  Cases with largest instruction count differences:")
    diff_sorted = sorted(mismatches, key=lambda m: abs(m["origSize"] - m["recompSize"]), reverse=True)
    for m in diff_sorted[:5]:
        delta = m["origSize"] - m["recompSize"]
        print(f"    {m['class']}#{m['method']}: orig={m['origSize']}, recomp={m['recompSize']}, delta={delta}")

    # Find cases where same condition opcode appears on both sides (not an inversion!)
    print("\n  Cases where divergence opcodes are the SAME (not inverse):")
    shown = 0
    for m in mismatches:
        orig_at, recomp_at = extract_divergence_opcodes(m["origContext"], m["recompContext"], m["divergeIndex"])
        if orig_at and recomp_at and orig_at[0] == recomp_at[0] and orig_at[0].startswith("IF"):
            print(f"    {m['class']}#{m['method']} @ #{m['divergeIndex']}: both {orig_at[0]} (targets: {orig_at[1]} vs {recomp_at[1]})")
            shown += 1
            if shown >= 5:
                break
    if shown == 0:
        print("    (none found)")


if __name__ == "__main__":
    main()
