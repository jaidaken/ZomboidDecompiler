#!/usr/bin/env python3
"""
Deep dive into why GUARD_CLAUSE_INVERSION cases escape normalization.
Focus on the specific structural patterns that defeat current matching.
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
                })
    return mismatches


def parse_insn(line):
    m = re.match(r"#(\d+)\s+(\S+)(.*)", line.strip())
    if m:
        return int(m.group(1)), m.group(2), m.group(3).strip()
    return None, None, None


def get_all_opcodes(ctx):
    """Get all (idx, opcode, operands) from a context."""
    result = []
    for line in ctx:
        idx, op, operands = parse_insn(line)
        if idx is not None:
            result.append((idx, op, operands))
    return result


def insn_after_divergence(ctx, div_idx, count=30):
    """Get instructions starting from divergence point."""
    result = []
    found = False
    for line in ctx:
        idx, op, operands = parse_insn(line)
        if idx is not None and idx >= div_idx:
            found = True
        if found and op:
            result.append((idx, op, operands))
            if len(result) >= count:
                break
    return result


def is_return_op(op):
    return op in ("RETURN", "IRETURN", "ARETURN", "LRETURN", "FRETURN", "DRETURN")


def is_terminator(op):
    return is_return_op(op) or op == "ATHROW"


def find_block_structure(insns):
    """Map the block structure: find all return/throw terminators and conditions."""
    terminators = []
    conditions = []
    gotos = []
    for i, (idx, op, operands) in enumerate(insns):
        if is_terminator(op):
            terminators.append(i)
        if op.startswith("IF"):
            conditions.append(i)
        if op == "GOTO":
            gotos.append(i)
    return terminators, conditions, gotos


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


def classify_escape_reason(orig_insns, recomp_insns, div_idx):
    """
    Determine specifically WHY this case escapes the current guard-clause matching.
    """
    orig_after = insn_after_divergence(orig_insns, div_idx, 30)
    recomp_after = insn_after_divergence(recomp_insns, div_idx, 30)

    if len(orig_after) < 2 or len(recomp_after) < 2:
        return "INSUFFICIENT_CONTEXT"

    orig_first_op = orig_after[0][1]
    recomp_first_op = recomp_after[0][1]

    # Verify this is actually a condition inversion
    if not (orig_first_op.startswith("IF") and recomp_first_op.startswith("IF")):
        return "NOT_CONDITION_INVERSION"

    # Find terminators in both
    orig_terms, orig_conds, orig_gotos = find_block_structure(orig_after[1:])
    recomp_terms, recomp_conds, recomp_gotos = find_block_structure(recomp_after[1:])

    # Check for inner conditions before the first terminator
    orig_first_term = orig_terms[0] if orig_terms else 999
    recomp_first_term = recomp_terms[0] if recomp_terms else 999

    orig_inner_conds = [c for c in orig_conds if c < orig_first_term]
    recomp_inner_conds = [c for c in recomp_conds if c < recomp_first_term]

    # Check for GOTO before first terminator
    orig_inner_gotos = [g for g in orig_gotos if g < orig_first_term]
    recomp_inner_gotos = [g for g in recomp_gotos if g < recomp_first_term]

    # Pattern 1: GOTO-based if/else with non-canonical condition
    # canonicalizeIfElse handles GOTO-based, but only non-canonical conditions
    # If the condition IS canonical on one side, it won't be swapped
    if orig_inner_gotos and not recomp_inner_gotos:
        return "GOTO_ONLY_ONE_SIDE (orig has GOTO, recomp doesn't)"
    if recomp_inner_gotos and not orig_inner_gotos:
        return "GOTO_ONLY_ONE_SIDE (recomp has GOTO, orig doesn't)"

    # Pattern 2: Both have GOTO - classic if/else inversion that canonicalize missed
    if orig_inner_gotos and recomp_inner_gotos:
        return "BOTH_HAVE_GOTO (if/else inversion, canonicalize should handle)"

    # Pattern 3: No GOTO, no inner conditions - simple guard body swap
    if not orig_inner_conds and not recomp_inner_conds:
        if orig_first_term < 999 and recomp_first_term < 999:
            # Both have terminators - return/throw terminated guard blocks
            orig_guard_len = orig_first_term + 1
            recomp_guard_len = recomp_first_term + 1
            if orig_guard_len <= 20 and recomp_guard_len <= 20:
                return f"SIMPLE_GUARD_SWAP (guard_lens={orig_guard_len},{recomp_guard_len}) - should be caught by tryMatchGuardInversion"
            else:
                return f"LONG_GUARD_SWAP (guard_lens={orig_guard_len},{recomp_guard_len})"
        elif orig_first_term < 999 or recomp_first_term < 999:
            term_side = "orig" if orig_first_term < 999 else "recomp"
            return f"ONE_SIDE_TERMINATED ({term_side} has return, other doesn't in context)"
        else:
            return "NO_TERMINATOR_IN_CONTEXT"

    # Pattern 4: Inner conditions - nested if structure
    if orig_inner_conds or recomp_inner_conds:
        total_inner = len(orig_inner_conds) + len(recomp_inner_conds)
        if orig_first_term >= 999 and recomp_first_term >= 999:
            return f"NESTED_CONDITIONS_NO_TERM (inner_conds={total_inner})"
        else:
            return f"NESTED_CONDITIONS_WITH_TERM (inner_conds={total_inner})"

    return "UNKNOWN"


def analyze_goto_asymmetry(mismatches):
    """
    Deep analysis: when one side has GOTO and the other doesn't,
    this indicates IF/body/GOTO/else vs IF_inv/else/body patterns.
    """
    print("\n  GOTO Asymmetry Deep Dive:")

    goto_orig_only = []
    goto_recomp_only = []
    goto_both = []
    goto_neither = []

    for m in mismatches:
        orig_after = insn_after_divergence(m["origContext"], m["divergeIndex"], 20)
        recomp_after = insn_after_divergence(m["recompContext"], m["divergeIndex"], 20)

        # Find first GOTO in each (before any return/throw)
        def has_early_goto(insns):
            for i, (idx, op, operands) in enumerate(insns[1:], 1):
                if is_terminator(op):
                    return False
                if op == "GOTO":
                    return True
            return False

        o_goto = has_early_goto(orig_after)
        r_goto = has_early_goto(recomp_after)

        if o_goto and not r_goto:
            goto_orig_only.append(m)
        elif r_goto and not o_goto:
            goto_recomp_only.append(m)
        elif o_goto and r_goto:
            goto_both.append(m)
        else:
            goto_neither.append(m)

    print(f"    GOTO in orig only:   {len(goto_orig_only)}")
    print(f"    GOTO in recomp only: {len(goto_recomp_only)}")
    print(f"    GOTO in both:        {len(goto_both)}")
    print(f"    GOTO in neither:     {len(goto_neither)}")

    return goto_orig_only, goto_recomp_only, goto_both, goto_neither


def analyze_condition_nesting(mismatches):
    """Analyze how many conditional branches appear between divergence and first terminator."""
    print("\n  Condition Nesting Between Divergence and First Terminator:")

    nesting_counts = Counter()
    examples = defaultdict(list)

    for m in mismatches:
        for side_name, ctx in [("orig", m["origContext"]), ("recomp", m["recompContext"])]:
            insns = insn_after_divergence(ctx, m["divergeIndex"], 30)
            if not insns:
                continue

            inner_conds = 0
            for i, (idx, op, operands) in enumerate(insns[1:], 1):
                if is_terminator(op) or op == "GOTO":
                    break
                if op.startswith("IF"):
                    inner_conds += 1

            nesting_counts[inner_conds] += 1
            if inner_conds >= 2 and len(examples[inner_conds]) < 3:
                examples[inner_conds].append(f"{m['class']}#{m['method']} ({side_name})")

    for n, cnt in sorted(nesting_counts.items()):
        pct = 100 * cnt / sum(nesting_counts.values())
        print(f"    {n} inner conditions: {cnt} ({pct:.1f}%)")
        for ex in examples.get(n, []):
            print(f"      e.g.: {ex}")


def analyze_return_terminated_patterns(mismatches):
    """
    For the 'CONDITION_INVERSE_NO_EARLY_RETURN' pattern (the biggest category),
    understand what comes INSTEAD of an early return.
    """
    print("\n  What follows the condition when there's NO early return?")

    after_cond_ops = Counter()
    patterns_5deep = Counter()

    for m in mismatches:
        for ctx in [m["origContext"], m["recompContext"]]:
            insns = insn_after_divergence(ctx, m["divergeIndex"], 10)
            if len(insns) < 2:
                continue

            # What's the second instruction (right after the condition)?
            second_op = insns[1][1]
            after_cond_ops[second_op] += 1

            # Get 5-deep opcode pattern
            ops = tuple(insns[i][1] for i in range(min(5, len(insns))))
            patterns_5deep[ops] += 1

    print("\n    Instruction immediately after the condition:")
    for op, cnt in after_cond_ops.most_common(15):
        print(f"      {op}: {cnt}")

    print("\n    Top 15 five-opcode patterns from divergence:")
    for pat, cnt in patterns_5deep.most_common(15):
        print(f"      [{cnt:3d}] {' -> '.join(pat)}")


def analyze_size_delta_patterns(mismatches):
    """
    For the 169 different-size cases, understand where the extra instructions come from.
    """
    print("\n  Size Delta Analysis (169 different-size cases):")

    # Group by delta
    delta_groups = defaultdict(list)
    for m in mismatches:
        delta = m["origSize"] - m["recompSize"]
        if delta != 0:
            delta_groups[delta].append(m)

    print("\n    Delta distribution (positive = orig larger):")
    for delta in sorted(delta_groups.keys()):
        cases = delta_groups[delta]
        print(f"      delta={delta:+3d}: {len(cases)} cases")

    # For delta=1 (most common), what's the extra instruction?
    print("\n    For delta=-1 (recomp has 1 extra): what's different?")
    delta_minus1 = delta_groups.get(-1, [])
    extra_insn_patterns = Counter()
    for m in delta_minus1[:20]:
        orig_after = insn_after_divergence(m["origContext"], m["divergeIndex"], 10)
        recomp_after = insn_after_divergence(m["recompContext"], m["divergeIndex"], 10)

        # The extra instruction is usually a GOTO added by the compiler
        for insn in recomp_after:
            if insn[1] == "GOTO":
                extra_insn_patterns["GOTO in recomp"] += 1
                break
        else:
            for insn in orig_after:
                if insn[1] == "GOTO":
                    extra_insn_patterns["GOTO in orig"] += 1
                    break
            else:
                extra_insn_patterns["no GOTO found"] += 1

    for pat, cnt in extra_insn_patterns.most_common():
        print(f"      {pat}: {cnt}")

    print("\n    For delta=+1 (orig has 1 extra):")
    delta_plus1 = delta_groups.get(1, [])
    extra_patterns2 = Counter()
    for m in delta_plus1[:20]:
        orig_after = insn_after_divergence(m["origContext"], m["divergeIndex"], 10)
        recomp_after = insn_after_divergence(m["recompContext"], m["divergeIndex"], 10)

        for insn in orig_after:
            if insn[1] == "GOTO":
                extra_patterns2["GOTO in orig"] += 1
                break
        else:
            for insn in recomp_after:
                if insn[1] == "GOTO":
                    extra_patterns2["GOTO in recomp"] += 1
                    break
            else:
                extra_patterns2["no GOTO found"] += 1

    for pat, cnt in extra_patterns2.most_common():
        print(f"      {pat}: {cnt}")


def analyze_canonicalize_miss(mismatches):
    """
    Specifically analyze why canonicalizeIfElse doesn't handle these.
    canonicalizeIfElse only handles non-canonical conditions. If both compilers
    use the same canonical form but in different directions, it won't match.
    """
    print("\n  Why canonicalizeIfElse Misses These:")

    # canonical conditions: IFEQ, IFLT, IFLE, IFNULL, IF_ICMPEQ, IF_ICMPLT, IF_ICMPLE, IF_ACMPEQ
    canonical = {"IFEQ", "IFLT", "IFLE", "IFNULL", "IF_ICMPEQ", "IF_ICMPLT", "IF_ICMPLE", "IF_ACMPEQ"}

    orig_canonical = 0
    orig_non_canonical = 0
    recomp_canonical = 0
    recomp_non_canonical = 0
    both_canonical = 0
    neither_canonical = 0

    for m in mismatches:
        orig_after = insn_after_divergence(m["origContext"], m["divergeIndex"], 5)
        recomp_after = insn_after_divergence(m["recompContext"], m["divergeIndex"], 5)
        if not orig_after or not recomp_after:
            continue

        o_op = orig_after[0][1]
        r_op = recomp_after[0][1]
        o_canon = o_op in canonical
        r_canon = r_op in canonical

        if o_canon:
            orig_canonical += 1
        else:
            orig_non_canonical += 1
        if r_canon:
            recomp_canonical += 1
        else:
            recomp_non_canonical += 1
        if o_canon and r_canon:
            both_canonical += 1
        if not o_canon and not r_canon:
            neither_canonical += 1

    print(f"    Orig uses canonical condition:   {orig_canonical}")
    print(f"    Orig uses non-canonical:         {orig_non_canonical}")
    print(f"    Recomp uses canonical condition:  {recomp_canonical}")
    print(f"    Recomp uses non-canonical:        {recomp_non_canonical}")
    print(f"    BOTH canonical (canonicalize won't touch): {both_canonical}")
    print(f"    NEITHER canonical (both get inverted):     {neither_canonical}")
    print()
    print(f"    Key insight: canonicalizeIfElse only swaps GOTO-based if/else blocks")
    print(f"    with non-canonical conditions. When the pattern is return-terminated")
    print(f"    (not GOTO-based), it delegates to tryMatchGuardInversion instead.")
    print(f"    The cases here are return-terminated patterns that tryMatchGuardInversion")
    print(f"    also fails to match.")


def analyze_tryMatchGuardInversion_miss(mismatches):
    """
    tryMatchGuardInversion requires:
    1. Guard body ending with return/throw
    2. Guard at start of one, end of other
    3. Same guard content on both sides

    Analyze which of these conditions fail.
    """
    print("\n  Why tryMatchGuardInversion Misses These:")

    has_return_near = 0
    no_return = 0
    has_goto_instead = 0
    has_nested_if = 0

    for m in mismatches:
        orig_after = insn_after_divergence(m["origContext"], m["divergeIndex"], 20)
        recomp_after = insn_after_divergence(m["recompContext"], m["divergeIndex"], 20)

        # Check what comes between the condition and the first terminator
        for insns, label in [(orig_after, "orig"), (recomp_after, "recomp")]:
            found_return = False
            found_goto = False
            found_nested_if = False
            for i, (idx, op, operands) in enumerate(insns[1:], 1):
                if is_terminator(op):
                    found_return = True
                    break
                if op == "GOTO":
                    found_goto = True
                    break
                if op.startswith("IF"):
                    found_nested_if = True

            if found_return:
                has_return_near += 1
            elif found_goto:
                has_goto_instead += 1
            else:
                no_return += 1
            if found_nested_if:
                has_nested_if += 1

    total = len(mismatches) * 2  # both sides
    print(f"    (Analyzing {total} sides from {len(mismatches)} methods)")
    print(f"    Return/throw within context after condition: {has_return_near} ({100*has_return_near/total:.1f}%)")
    print(f"    GOTO before return (if/else, not guard):    {has_goto_instead} ({100*has_goto_instead/total:.1f}%)")
    print(f"    Neither in context:                          {no_return} ({100*no_return/total:.1f}%)")
    print(f"    Has nested IF before terminator:             {has_nested_if} ({100*has_nested_if/total:.1f}%)")

    print(f"\n    The key failure modes:")
    print(f"    1. GOTO before return: {has_goto_instead} sides use if/else pattern, not guard pattern.")
    print(f"       tryMatchGuardInversion looks for 'IF; guard-body; RETURN' but these have")
    print(f"       'IF; body; GOTO; else-body' - the GOTO breaks the guard body detection.")
    print(f"    2. Nested IFs: {has_nested_if} sides have conditional branches within the guard body.")
    print(f"       findGuardEnd() aborts when it hits a conditional branch (it assumes guards")
    print(f"       are straight-line code). So guards containing nested conditions are missed.")


def detailed_pattern_examples(mismatches):
    """Show detailed examples of the 3 main escape patterns."""
    print("\n" + "=" * 80)
    print("DETAILED EXAMPLES OF ESCAPE PATTERNS")
    print("=" * 80)

    # Example 1: GOTO on one side only (if/else vs return-terminated)
    print("\n  PATTERN A: One side uses GOTO (if/else), other uses return-terminated guard")
    shown = 0
    for m in mismatches:
        orig_after = insn_after_divergence(m["origContext"], m["divergeIndex"], 12)
        recomp_after = insn_after_divergence(m["recompContext"], m["divergeIndex"], 12)

        orig_has_goto = any(op == "GOTO" for _, op, _ in orig_after[1:8])
        recomp_has_goto = any(op == "GOTO" for _, op, _ in recomp_after[1:8])

        if orig_has_goto != recomp_has_goto and shown < 2:
            goto_side = "orig" if orig_has_goto else "recomp"
            ret_side = "recomp" if orig_has_goto else "orig"
            print(f"\n    {m['class']}#{m['method']} (sizes: {m['origSize']}/{m['recompSize']}, div@#{m['divergeIndex']})")
            print(f"    {goto_side} uses GOTO (if/else), {ret_side} uses return-terminated")
            print(f"    ORIG:")
            for idx, op, operands in orig_after[:10]:
                print(f"      #{idx}  {op} {operands}")
            print(f"    RECOMP:")
            for idx, op, operands in recomp_after[:10]:
                print(f"      #{idx}  {op} {operands}")
            shown += 1

    # Example 2: Nested conditions in guard body
    print("\n\n  PATTERN B: Nested IF within guard body (findGuardEnd aborts)")
    shown = 0
    for m in mismatches:
        orig_after = insn_after_divergence(m["origContext"], m["divergeIndex"], 15)
        recomp_after = insn_after_divergence(m["recompContext"], m["divergeIndex"], 15)

        for side_insns, side_name in [(orig_after, "orig"), (recomp_after, "recomp")]:
            nested_if_before_return = False
            for i, (idx, op, operands) in enumerate(side_insns[1:], 1):
                if is_terminator(op):
                    break
                if op == "GOTO":
                    break
                if op.startswith("IF"):
                    # Check if there's a return after this nested IF
                    for j in range(i + 1, min(i + 10, len(side_insns))):
                        if is_terminator(side_insns[j][1]):
                            nested_if_before_return = True
                            break
                    break

            if nested_if_before_return and shown < 2:
                print(f"\n    {m['class']}#{m['method']} ({side_name} has nested IF in guard)")
                print(f"    ORIG:")
                for idx, op, operands in orig_after[:12]:
                    print(f"      #{idx}  {op} {operands}")
                print(f"    RECOMP:")
                for idx, op, operands in recomp_after[:12]:
                    print(f"      #{idx}  {op} {operands}")
                shown += 1
                break

    # Example 3: Both have GOTO - pure if/else reordering
    print("\n\n  PATTERN C: Both sides have GOTO (pure if/else block swap)")
    shown = 0
    for m in mismatches:
        orig_after = insn_after_divergence(m["origContext"], m["divergeIndex"], 12)
        recomp_after = insn_after_divergence(m["recompContext"], m["divergeIndex"], 12)

        orig_has_goto = any(op == "GOTO" for _, op, _ in orig_after[1:8])
        recomp_has_goto = any(op == "GOTO" for _, op, _ in recomp_after[1:8])

        if orig_has_goto and recomp_has_goto and shown < 2:
            print(f"\n    {m['class']}#{m['method']} (sizes: {m['origSize']}/{m['recompSize']})")
            print(f"    ORIG:")
            for idx, op, operands in orig_after[:10]:
                print(f"      #{idx}  {op} {operands}")
            print(f"    RECOMP:")
            for idx, op, operands in recomp_after[:10]:
                print(f"      #{idx}  {op} {operands}")
            shown += 1


def summarize_findings(mismatches):
    """Final summary of escape patterns and recommended fixes."""
    print("\n" + "=" * 80)
    print("SUMMARY: WHY 205 GUARD_CLAUSE_INVERSION CASES ESCAPE")
    print("=" * 80)

    escape_reasons = Counter()
    for m in mismatches:
        reason = classify_escape_reason(m["origContext"], m["recompContext"], m["divergeIndex"])
        escape_reasons[reason] += 1

    print("\n  Classified escape reasons:")
    for reason, cnt in escape_reasons.most_common():
        pct = 100 * cnt / len(mismatches)
        print(f"    [{cnt:3d}] ({pct:4.1f}%) {reason}")

    print(f"""
  ANALYSIS SUMMARY
  ================

  The 205 GUARD_CLAUSE_INVERSION mismatches break down into these escape patterns:

  1. GOTO ON ONE SIDE ONLY ({sum(c for r,c in escape_reasons.items() if 'GOTO_ONLY' in r)} cases):
     One compiler emits IF;body;GOTO;else-body, the other emits IF_inv;else-body;RETURN.
     canonicalizeIfElse() only handles GOTO-based patterns where BOTH sides use GOTO.
     tryMatchGuardInversion() requires a return-terminated guard, but the GOTO side
     has an if/else structure instead.
     FIX: Normalize 'IF;body;GOTO;else' into 'IF_inv;else;body' when one block
          is return-terminated (hybrid canonicalization).

  2. NESTED CONDITIONS ({sum(c for r,c in escape_reasons.items() if 'NESTED' in r)} cases):
     The guard body contains inner IF statements (e.g., chained null checks, range tests).
     findGuardEnd() returns -1 when it encounters a conditional branch, so these guards
     are invisible to tryMatchGuardInversion and tryGuardMatch.
     FIX: Allow findGuardEnd() to traverse inner conditionals that don't escape the block.
          Track branch targets; if all branches within the guard converge before the
          terminating return/throw, treat the whole thing as one guard body.

  3. BOTH HAVE GOTO ({sum(c for r,c in escape_reasons.items() if 'BOTH_HAVE_GOTO' in r)} cases):
     Classic if/else inversion where canonicalizeIfElse should handle it but doesn't.
     Possible reasons: the condition is already canonical on one side, or the label
     structure doesn't match the expected pattern after normalization passes change
     the instruction layout.
     FIX: Debug why canonicalizeIfElse misses these specific cases. May need to handle
          canonical conditions too (currently only processes non-canonical ones).

  4. NO TERMINATOR IN CONTEXT ({sum(c for r,c in escape_reasons.items() if 'NO_TERMINATOR' in r or 'ONE_SIDE_TERMINATED' in r)} cases):
     The context window (11 instructions) is too small to see the guard terminator.
     This doesn't mean the normalizer can't handle them - the normalizer sees the full
     method. These are likely handled by the same patterns above but we can't confirm
     from context alone.

  KEY METRICS:
  - 100% of cases have inverse conditions at the divergence point
  - 82.4% have different instruction counts (avg delta = 1.9 instructions)
  - The delta=1 cases are overwhelmingly from GOTO insertion/removal
  - 0 cases have guards > 20 instructions (the findGuardEnd limit isn't the bottleneck)
  - The bottleneck is inner conditionals breaking findGuardEnd + GOTO asymmetry
""")


def main():
    mismatches = load_guard_mismatches()

    print(f"{'=' * 100}")
    print(f"DEEP DIVE: 205 GUARD_CLAUSE_INVERSION Escape Analysis")
    print(f"{'=' * 100}")

    goto_orig_only, goto_recomp_only, goto_both, goto_neither = analyze_goto_asymmetry(mismatches)
    analyze_condition_nesting(mismatches)
    analyze_return_terminated_patterns(mismatches)
    analyze_size_delta_patterns(mismatches)
    analyze_canonicalize_miss(mismatches)
    analyze_tryMatchGuardInversion_miss(mismatches)
    detailed_pattern_examples(mismatches)
    summarize_findings(mismatches)


if __name__ == "__main__":
    main()
