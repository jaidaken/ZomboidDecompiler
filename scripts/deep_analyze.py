#!/usr/bin/env python3
"""
Deep analysis of every non-EXACT method. Compares original vs recompiled
bytecode instruction-by-instruction, classifies the difference pattern,
and outputs a detailed CSV.

Usage:
  python3 scripts/deep_analyze.py progress/b41/report.json \
      /home/jaidaken/optizomb/build-41/vanilla-game-41/projectzomboid \
      /home/jaidaken/optizomb/build-41/Recompiled-game \
      > analysis.csv
"""

import csv
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from typing import Optional

JAVAP = "/home/jaidaken/optizomb/tools/zulu-jdk-17.0.1/bin/javap"

# Branch inversion pairs
INVERSIONS = {
    'ifeq': 'ifne', 'ifne': 'ifeq',
    'iflt': 'ifge', 'ifge': 'iflt',
    'ifgt': 'ifle', 'ifle': 'ifgt',
    'ifnull': 'ifnonnull', 'ifnonnull': 'ifnull',
    'if_icmpeq': 'if_icmpne', 'if_icmpne': 'if_icmpeq',
    'if_icmplt': 'if_icmpge', 'if_icmpge': 'if_icmplt',
    'if_icmpgt': 'if_icmple', 'if_icmple': 'if_icmpgt',
    'if_acmpeq': 'if_acmpne', 'if_acmpne': 'if_acmpeq',
}

RETURN_OPS = {'return', 'ireturn', 'lreturn', 'freturn', 'dreturn', 'areturn'}


_javap_cache: dict[str, str] = {}

def get_method_bytecode(classdir: str, classname: str, methodname: str, descriptor: str):
    """Get instruction list for a specific method via javap -v, matching by descriptor."""
    classfile = f"{classdir}/{classname}.class"
    cache_key = classfile
    if cache_key not in _javap_cache:
        try:
            _javap_cache[cache_key] = subprocess.check_output(
                [JAVAP, "-v", "-c", "-p", classfile],
                stderr=subprocess.DEVNULL, text=True, timeout=30
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError):
            _javap_cache[cache_key] = ""
            return None, None

    out = _javap_cache[cache_key]
    if not out:
        return None, None

    lines = out.split('\n')
    # Derive simple class name for <init> matching
    simple_class = classname.split('/')[-1].split('$')[-1]

    i = 0
    while i < len(lines):
        line = lines[i]
        # Match method declaration
        # <clinit> appears as "static {};"
        # <init> appears as "ClassName(params);"
        # Normal methods appear as "returnType methodName(params);"
        is_match = False
        if methodname == '<clinit>' and 'static {};' in line:
            is_match = True
        elif methodname == '<init>' and simple_class + '(' in line:
            is_match = True
        elif methodname not in ('<init>', '<clinit>') and methodname + '(' in line:
            # Ensure it's a method declaration line, not a constant pool reference
            stripped = line.strip()
            # Declaration lines start with modifiers or return types, not # or "
            if not stripped.startswith('#') and not stripped.startswith('"') \
                    and not stripped.startswith('//') and '=' not in stripped.split('(')[0]:
                is_match = True

        if is_match:
            # Check next lines for "descriptor: <desc>"
            desc_match = False
            for k in range(i + 1, min(i + 5, len(lines))):
                dm = re.match(r'\s+descriptor:\s+(\S+)', lines[k])
                if dm:
                    if dm.group(1) == descriptor:
                        desc_match = True
                    break

            if not desc_match:
                i += 1
                continue

            # Found the right method - parse instructions
            insns = []
            exc_table = []
            in_code = False
            in_exc = False
            j = i + 1
            while j < len(lines):
                l = lines[j]
                if l.strip() == 'Code:':
                    in_code = True
                    j += 1
                    continue
                if in_code:
                    m = re.match(r'\s+(\d+):\s+(\w+)(.*)', l)
                    if m:
                        offset = int(m.group(1))
                        opcode = m.group(2)
                        operand = m.group(3).strip()
                        if '//' in operand:
                            operand = operand[:operand.index('//')].strip()
                        insns.append((offset, opcode, operand))
                        in_exc = False
                    elif 'Exception table:' in l:
                        in_exc = True
                    elif in_exc:
                        em = re.match(r'\s+from\s+to\s+target\s+type', l)
                        if not em:
                            em = re.match(r'\s+(\d+)\s+(\d+)\s+(\d+)\s+(.*)', l)
                            if em:
                                exc_table.append({
                                    'from': int(em.group(1)),
                                    'to': int(em.group(2)),
                                    'target': int(em.group(3)),
                                    'type': em.group(4).strip()
                                })
                    elif re.match(r'\s+(public|private|protected|static|final|synchronized|native|abstract|volatile)', l) and insns:
                        break
                    elif l.strip() == '' and insns:
                        # Could be blank line in code section, check next line
                        if j + 1 < len(lines) and re.match(r'\s+\d+:', lines[j + 1]):
                            j += 1
                            continue
                        break
                    elif l.strip().startswith('StackMapTable:') or l.strip().startswith('LineNumberTable:') or l.strip().startswith('LocalVariableTable:'):
                        break
                j += 1
            if insns:
                return insns, exc_table
        i += 1

    return None, None


def classify_diff(orig_insns, recomp_insns, orig_exc, recomp_exc):
    """Deep classification of the difference between two instruction sequences."""
    if orig_insns is None or recomp_insns is None:
        return 'MISSING_BYTECODE', 'Could not load bytecode for comparison', []

    oo = [x[1] for x in orig_insns]  # opcodes only
    ro = [x[1] for x in recomp_insns]
    diff = len(ro) - len(oo)

    # Find all opcode differences at aligned positions
    min_len = min(len(oo), len(ro))
    first_diff_idx = -1
    opcode_diffs = []
    for i in range(min_len):
        if oo[i] != ro[i]:
            if first_diff_idx == -1:
                first_diff_idx = i
            opcode_diffs.append((i, oo[i], ro[i]))

    if first_diff_idx == -1 and diff == 0:
        # Opcodes identical - diff must be in operands only
        operand_diffs = []
        for i in range(len(orig_insns)):
            if orig_insns[i][2] != recomp_insns[i][2]:
                operand_diffs.append((i, orig_insns[i], recomp_insns[i]))
        if not operand_diffs:
            # Exception table diff?
            if orig_exc != recomp_exc:
                return 'EXCEPTION_TABLE_DIFF', f'Exception tables differ', operand_diffs
            return 'IDENTICAL', 'Instructions appear identical', []

        # Classify operand diffs
        branch_target_diffs = []
        const_pool_diffs = []
        var_slot_diffs = []
        for idx, orig, recomp in operand_diffs:
            op = orig[1]
            if op in INVERSIONS or op == 'goto' or op.startswith('if'):
                branch_target_diffs.append((idx, orig, recomp))
            elif op.startswith('aload') or op.startswith('astore') or op.startswith('iload') or op.startswith('istore'):
                var_slot_diffs.append((idx, orig, recomp))
            else:
                const_pool_diffs.append((idx, orig, recomp))

        if branch_target_diffs and not const_pool_diffs and not var_slot_diffs:
            targets = '; '.join(f'#{d[0]} {d[1][1]} {d[1][2]} vs {d[2][2]}' for d in branch_target_diffs[:3])
            return 'BRANCH_TARGET_DIFF', f'{len(branch_target_diffs)} branch targets differ: {targets}', operand_diffs
        if var_slot_diffs and not const_pool_diffs and not branch_target_diffs:
            return 'VAR_SLOT_DIFF', f'{len(var_slot_diffs)} variable slots differ', operand_diffs
        if const_pool_diffs:
            return 'CONST_POOL_DIFF', f'{len(const_pool_diffs)} constant pool refs differ', operand_diffs
        desc = f'Mixed operand diffs: {len(branch_target_diffs)} branch, {len(var_slot_diffs)} var, {len(const_pool_diffs)} const'
        return 'MIXED_OPERAND_DIFF', desc, operand_diffs

    # Opcodes differ - classify the pattern
    if diff == 0:
        # Same count, different opcodes
        inv_count = 0
        goto_ret_swaps = 0
        other_swaps = []
        for idx, orig_op, recomp_op in opcode_diffs:
            if INVERSIONS.get(recomp_op) == orig_op:
                inv_count += 1
            elif (orig_op in RETURN_OPS and recomp_op == 'goto') or \
                 (recomp_op in RETURN_OPS and orig_op == 'goto'):
                goto_ret_swaps += 1
            else:
                other_swaps.append((idx, orig_op, recomp_op))

        if inv_count > 0 and not other_swaps and goto_ret_swaps == 0:
            detail = '; '.join(f'#{d[0]} {d[2]}->{d[1]}' for d in opcode_diffs[:3])
            return 'BRANCH_INVERSION_ONLY', f'{inv_count} inversions: {detail}', opcode_diffs
        if goto_ret_swaps > 0 and not other_swaps and inv_count == 0:
            detail = '; '.join(f'#{d[0]} {d[2]}->{d[1]}' for d in opcode_diffs[:3])
            return 'GOTO_RETURN_SWAP', f'{goto_ret_swaps} goto/return swaps: {detail}', opcode_diffs
        if inv_count > 0 and goto_ret_swaps > 0 and not other_swaps:
            return 'INV_PLUS_GOTO_SWAP', f'{inv_count} inversions + {goto_ret_swaps} goto/ret swaps', opcode_diffs
        if inv_count > 0 and other_swaps:
            # Inversion with surrounding code reshuffled
            return 'BRANCH_INV_RESTRUCTURED', \
                f'{inv_count} inv + {len(other_swaps)} other opcode diffs (block reorder)', opcode_diffs
        if other_swaps and not inv_count:
            detail = '; '.join(f'#{s[0]} {s[2]}->{s[1]}' for s in other_swaps[:3])
            return 'OPCODE_DIFF', f'{len(other_swaps)} opcode differences: {detail}', opcode_diffs

    # Different instruction counts
    orig_gotos = sum(1 for op in oo if op == 'goto')
    recomp_gotos = sum(1 for op in ro if op == 'goto')
    orig_returns = sum(1 for op in oo if op in RETURN_OPS)
    recomp_returns = sum(1 for op in ro if op in RETURN_OPS)
    goto_diff = recomp_gotos - orig_gotos
    ret_diff = recomp_returns - orig_returns

    # Try to find what's extra/missing via LCS alignment
    extra_orig, extra_recomp = find_extras(oo, ro)

    if diff == -1:
        if extra_orig:
            ops = ', '.join(f'{op}' for op in extra_orig[:3])
            if all(op == 'goto' for op in extra_orig):
                return 'ORIG_EXTRA_GOTO', f'Original has {len(extra_orig)} extra goto(s)', []
            if all(op in RETURN_OPS for op in extra_orig):
                return 'ORIG_EXTRA_RETURN', f'Original has extra return', []
            return 'ORIG_EXTRA_INSN', f'Original has extra: {ops}', []

    if diff == 1:
        if extra_recomp:
            ops = ', '.join(f'{op}' for op in extra_recomp[:3])
            if all(op == 'goto' for op in extra_recomp):
                return 'RECOMP_EXTRA_GOTO', f'Recomp has {len(extra_recomp)} extra goto(s)', []
            if all(op in RETURN_OPS for op in extra_recomp):
                return 'RECOMP_EXTRA_RETURN', f'Recomp has extra return', []
            return 'RECOMP_EXTRA_INSN', f'Recomp has extra: {ops}', []

    if diff == -2:
        if extra_orig:
            ops = Counter(extra_orig)
            return 'ORIG_EXTRA_2', f'Original has 2 extra insns: {dict(ops)}', []

    if diff == 2:
        if extra_recomp:
            ops = Counter(extra_recomp)
            return 'RECOMP_EXTRA_2', f'Recomp has 2 extra insns: {dict(ops)}', []

    # General case
    if abs(diff) <= 4:
        return f'COUNT_DIFF_{diff:+d}', \
            f'Instruction count diff={diff:+d}, goto diff={goto_diff:+d}, return diff={ret_diff:+d}', []

    # Large structural difference
    # Check if it's mainly block reordering (same multiset of opcodes?)
    orig_bag = Counter(oo)
    recomp_bag = Counter(ro)
    shared = sum((orig_bag & recomp_bag).values())
    total = max(sum(orig_bag.values()), sum(recomp_bag.values()))
    similarity = shared / total if total > 0 else 0

    if similarity > 0.95:
        added = recomp_bag - orig_bag
        removed = orig_bag - recomp_bag
        return 'BLOCK_REORDER', \
            f'Likely block reorder (sim={similarity:.1%}), added={dict(added)}, removed={dict(removed)}', []

    return f'LARGE_STRUCTURAL_DIFF_{diff:+d}', \
        f'Major structural diff={diff:+d}, similarity={similarity:.1%}', []


def find_extras(shorter_ops, longer_ops):
    """Find which opcodes are extra in the longer list via LCS."""
    a, b = shorter_ops, longer_ops
    if len(a) > len(b):
        a, b = b, a

    n, m = len(a), len(b)
    if n == 0:
        return [], list(b)

    # LCS via DP (memory optimized for large methods)
    if n * m > 500000:
        # Too large for full DP, use simple heuristic
        a_bag = Counter(a)
        b_bag = Counter(b)
        extra_in_b = list((b_bag - a_bag).elements())
        if shorter_ops is a:
            return [], extra_in_b
        return extra_in_b, []

    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if a[i-1] == b[j-1]:
                dp[i][j] = dp[i-1][j-1] + 1
            else:
                dp[i][j] = max(dp[i-1][j], dp[i][j-1])

    # Find unmatched in b
    matched_b = set()
    i, j = n, m
    while i > 0 and j > 0:
        if a[i-1] == b[j-1]:
            matched_b.add(j-1)
            i -= 1; j -= 1
        elif dp[i-1][j] > dp[i][j-1]:
            i -= 1
        else:
            j -= 1

    extra_in_b = [b[j] for j in range(m) if j not in matched_b]

    if shorter_ops is a:
        return [], extra_in_b
    return extra_in_b, []


def get_context(insns, idx, window=2):
    """Get a few instructions around the diff point."""
    if insns is None:
        return ''
    start = max(0, idx - window)
    end = min(len(insns), idx + window + 1)
    parts = []
    for i in range(start, end):
        marker = '>>>' if i == idx else '   '
        off, op, operand = insns[i]
        parts.append(f'{marker}{off}: {op} {operand}')
    return ' | '.join(parts)


def main():
    if len(sys.argv) < 4:
        print("Usage: deep_analyze.py <report.json> <orig_classes_dir> <recomp_classes_dir>",
              file=sys.stderr)
        sys.exit(1)

    report_path = sys.argv[1]
    orig_dir = sys.argv[2]
    recomp_dir = sys.argv[3]

    with open(report_path) as f:
        data = json.load(f)

    # Collect non-EXACT methods
    methods = []
    for unit in data['units']:
        if 'methods' not in unit:
            continue
        for m in unit['methods']:
            if m.get('matchTier') != 'EXACT':
                methods.append({
                    'class': unit['name'],
                    'method': m['name'],
                    'descriptor': m['descriptor'],
                    'tier': m['matchTier'],
                    'orig_insns': m['origInsnCount'],
                    'recomp_insns': m['recompInsnCount'],
                })

    print(f"Analyzing {len(methods)} non-EXACT methods...", file=sys.stderr)

    # CSV output
    writer = csv.writer(sys.stdout)
    writer.writerow([
        'class', 'method', 'descriptor', 'tier',
        'orig_insn_count', 'recomp_insn_count', 'insn_diff',
        'category', 'description', 'first_diff_idx',
        'orig_context', 'recomp_context'
    ])

    categories = Counter()
    for i, m in enumerate(methods):
        if (i + 1) % 50 == 0:
            print(f"  {i+1}/{len(methods)}...", file=sys.stderr)

        orig_insns, orig_exc = get_method_bytecode(
            orig_dir, m['class'], m['method'], m['descriptor'])
        recomp_insns, recomp_exc = get_method_bytecode(
            recomp_dir, m['class'], m['method'], m['descriptor'])

        category, description, diffs = classify_diff(
            orig_insns, recomp_insns, orig_exc, recomp_exc)

        categories[category] += 1

        # Find first diff index for context
        first_diff = -1
        if orig_insns and recomp_insns:
            for j in range(min(len(orig_insns), len(recomp_insns))):
                if orig_insns[j][1] != recomp_insns[j][1] or orig_insns[j][2] != recomp_insns[j][2]:
                    first_diff = j
                    break
            if first_diff == -1:
                first_diff = min(len(orig_insns), len(recomp_insns))

        orig_ctx = get_context(orig_insns, first_diff) if first_diff >= 0 else ''
        recomp_ctx = get_context(recomp_insns, first_diff) if first_diff >= 0 else ''

        diff_count = m['recomp_insns'] - m['orig_insns']
        writer.writerow([
            m['class'], m['method'], m['descriptor'], m['tier'],
            m['orig_insns'], m['recomp_insns'], diff_count,
            category, description, first_diff,
            orig_ctx, recomp_ctx
        ])

    # Summary to stderr
    print(f"\n{'='*60}", file=sys.stderr)
    print(f"Category breakdown ({len(methods)} methods):", file=sys.stderr)
    print(f"{'='*60}", file=sys.stderr)
    for cat, count in categories.most_common():
        pct = 100 * count / len(methods)
        print(f"  {cat:40s} {count:4d} ({pct:5.1f}%)", file=sys.stderr)


if __name__ == '__main__':
    main()
