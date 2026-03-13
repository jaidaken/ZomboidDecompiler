#!/usr/bin/env python3
"""
Source-level transforms to fix bytecode mismatches in three targeted classes.

Class 1: zombie/network/LoginQueue
  Pattern: Field renamed from `LoginQueue` to `s_loginQueue` by decompiler.
  The original .class has a static field named `LoginQueue` (same as class name).
  Vineflower renames it to `s_loginQueue` to disambiguate.
  Fix: Rename `s_loginQueue` back to `LoginQueue` in the source.
  Expected improvement: 9 methods fixed.

Class 2: zombie/core/Collections/ZomboidHashMap
  Pattern A (6 methods, diff=+3): `var` declarations create an extra ASTORE.
    The decompiler emits: `var object1 = entry.key;` then uses `entry.key` again
    for the identity check. This produces:
      GETFIELD key -> ASTORE v4 (store to var)
      GETFIELD key -> ALOAD v0 -> IF_ACMPEQ  (identity check using re-read)
    Original bytecode does NOT store to a temp var:
      GETFIELD key -> ALOAD v0 -> IF_ACMPEQ  (uses the value already on stack)
    The extra ASTORE + second GETFIELD = 3 extra instructions.
    Fix: Remove the `var objectN = entry.key;` line and use `entry.key` directly
    in the equals() call (the original uses the already-loaded key from stack).
    Actually, the real fix is to eliminate the temp var and restructure the condition.

  Pattern B (transfer method, diff=+3): `while(true) { ... if (x == null) break; }`
    compiles differently than `do { ... } while (x != null);`
    The decompiler produces: ASTORE v5 / ALOAD v6 / IFNULL (break) / GOTO (loop top)
    Original uses: IFNONNULL (loop back directly).
    Fix: Convert `while(true) { ...; entry0 = entry1; if (entry1 == null) break; }`
    to `do { ...; } while ((entry0 = entry1) != null);`

  Pattern C (2 methods): GUARD_CLAUSE_INVERSION - `if (!(x instanceof Y y))` pattern.
    Vineflower emits IFEQ (jump if false), original uses IFNE (jump if true) with
    early return. This is a known decompiler pattern mismatch.

  Pattern D (clone, diff=0): TRY_CATCH_DIFF - try-catch block metadata differs.

  Expected improvement: 6 methods (var pattern) + 1 (transfer) = 7 methods potentially.
  The GUARD_CLAUSE_INVERSION and TRY_CATCH_DIFF require decompiler-level fixes.

Class 3: zombie/inventory/ItemContainer
  Pattern: All 10 methods have try-finally with a return variable.
    Decompiler emits:
      InventoryItem item;
      try { item = this.getBest(...); } finally { ... }
      return item;
    This produces: ALOAD v4 / ARETURN after the finally block (2 extra insns).
    Original bytecode has the return INSIDE the try block (no separate return after
    the exception handler), so the `return item;` after the try-finally is unreachable
    but still compiled by javac.
    Fix: Move the return inside the try block:
      try { return this.getBest(...); } finally { ... }
    This eliminates the separate `ALOAD vN / ARETURN` after the exception handler.
  Expected improvement: 10 methods fixed.
"""

import re
import sys
import os

# ============================================================================
# CLASS 1: zombie/network/LoginQueue
# ============================================================================

def fix_login_queue(src_path):
    """Rename s_loginQueue -> LoginQueue (the field, not the class)."""
    with open(src_path) as f:
        content = f.read()

    original = content

    # The field declaration: `private static ArrayList<UdpConnection> s_loginQueue`
    # needs to become `private static ArrayList<UdpConnection> LoginQueue`
    # But we must be careful not to touch class references like `LoginQueue.` or
    # `class LoginQueue` or `LoginQueue.LoginQueueMessageType`.

    # Strategy: Only rename the field identifier `s_loginQueue` to `LoginQueue`.
    # This is safe because every occurrence of `s_loginQueue` refers to this field.
    content = content.replace('s_loginQueue', 'LoginQueue')

    if content != original:
        count = original.count('s_loginQueue')
        print(f"  LoginQueue: replaced {count} occurrences of 's_loginQueue' -> 'LoginQueue'")
        with open(src_path, 'w') as f:
            f.write(content)
        return count
    else:
        print("  LoginQueue: no changes needed")
        return 0


# ============================================================================
# CLASS 2: zombie/core/Collections/ZomboidHashMap - var pattern
# ============================================================================

def fix_zomboid_hashmap_var(src_path):
    """
    Remove redundant `var objectN = entry.key;` declarations where the var is used
    in a condition like `if (entry.key == object0 || object0.equals(object1))`.

    The pattern in the decompiled source is:
        var object1 = entry.key;
        if (entry.key == object0 || object0.equals(object1)) {

    In the original bytecode, `entry.key` is loaded once onto the stack, used for
    the identity comparison, and the same loaded value is used for the equals() call.
    The decompiler creates an extra local variable to hold it.

    The fix is to inline: remove the var line and replace `object1` with `entry.key`
    in the condition.

    Actually, looking more carefully at the bytecode:
    - Original: GETFIELD key / [stack value] / IF_ACMPEQ ... / ALOAD v0 / [key still in local from earlier load]
    - The original doesn't have a separate GETFIELD+ASTORE; it uses the key value
      that's already in a local from the for-loop variable or similar.

    The cleanest fix: remove `var objectN = entry.key;` and replace objectN usage
    with `entry.key` in the same scope.
    """
    with open(src_path) as f:
        content = f.read()

    original = content
    changes = 0

    # Pattern: "var <varname> = <expr>.key;\n" followed by usage in if-condition
    # We need to:
    # 1. Remove the `var objectN = entry.key;` line
    # 2. Replace `objectN` with `entry.key` in the subsequent condition

    # Find all occurrences of: var <id> = <expr>.key;
    # Then replace uses of <id> with <expr>.key in the enclosing scope
    var_pattern = re.compile(
        r'^(\s*)var (\w+) = ([\w.]+\.key);[ \t]*\n'
        r'(\s*if \(.*)',
        re.MULTILINE
    )

    def replace_var(match):
        nonlocal changes
        indent = match.group(1)
        varname = match.group(2)
        expr = match.group(3)
        if_line = match.group(4)
        # Replace varname with expr in the if-line
        new_if = if_line.replace(varname, expr)
        changes += 1
        return new_if

    content = var_pattern.sub(replace_var, content)

    # Also handle the pattern in removeMapping where it's:
    # var object1 = entry0.getKey();
    var_pattern2 = re.compile(
        r'^(\s*)var (\w+) = ([\w.]+\.getKey\(\));[ \t]*\n'
        r'(\s*int )',
        re.MULTILINE
    )

    def replace_var2(match):
        nonlocal changes
        varname = match.group(2)
        expr = match.group(3)
        rest = match.group(4)
        changes += 1
        return rest

    # This one is trickier - let's handle it with a broader approach
    # For removeMapping, the var is used later: hash(object1.hashCode())
    # Let's do a more targeted replacement

    if content != original:
        print(f"  ZomboidHashMap: applied {changes} var-removal transforms")
        with open(src_path, 'w') as f:
            f.write(content)
    else:
        print("  ZomboidHashMap: no var-removal changes applied")

    return changes


# ============================================================================
# CLASS 2: zombie/core/Collections/ZomboidHashMap - while(true) pattern
# ============================================================================

def fix_zomboid_hashmap_transfer(src_path):
    """
    Convert while(true) { ...; if (x == null) break; } to do { ... } while (x != null);
    in the transfer() method.
    """
    with open(src_path) as f:
        content = f.read()

    original = content

    # The specific pattern in transfer():
    old_pattern = """\
                while (true) {
                    ZomboidHashMap.Entry entry1 = entry0.next;
                    int int2 = indexFor(entry0.hash, int0);
                    entry0.next = entrys1[int2];
                    entrys1[int2] = entry0;
                    entry0 = entry1;
                    if (entry1 == null) {
                        break;
                    }
                }"""

    new_pattern = """\
                do {
                    ZomboidHashMap.Entry entry1 = entry0.next;
                    int int2 = indexFor(entry0.hash, int0);
                    entry0.next = entrys1[int2];
                    entrys1[int2] = entry0;
                    entry0 = entry1;
                } while (entry0 != null);"""

    if old_pattern in content:
        content = content.replace(old_pattern, new_pattern)
        print("  ZomboidHashMap transfer: converted while(true)+break to do-while")
        with open(src_path, 'w') as f:
            f.write(content)
        return 1
    else:
        print("  ZomboidHashMap transfer: pattern not found")
        return 0


# ============================================================================
# CLASS 3: zombie/inventory/ItemContainer - try-finally return pattern
# ============================================================================

def fix_item_container_try_return(src_path):
    """
    Convert:
        TypeName varName;
        try {
            varName = this.someMethod(...);
        } finally {
            ...
        }
        return varName;

    To:
        try {
            return this.someMethod(...);
        } finally {
            ...
        }

    This removes the 2 extra instructions (ALOAD + ARETURN) after the exception handler.
    """
    with open(src_path) as f:
        content = f.read()

    original = content
    changes = 0

    # Pattern: match the entire try-finally-return block
    # This is a multi-line pattern, so we use re.DOTALL
    pattern = re.compile(
        r'^(\s+)(InventoryItem) (\w+);\n'          # type varName;
        r'\1try \{\n'                               # try {
        r'\1    \3 = (this\.\w+\([^)]*\));\n'       # varName = this.method(...);
        r'\1\} finally \{\n'                         # } finally {
        r'((?:\1    [^\n]+\n)+)'                    # finally body (one or more lines)
        r'\1\}\n'                                   # }
        r'\n'                                       # blank line
        r'\1return \3;\n',                          # return varName;
        re.MULTILINE
    )

    def replace_try_return(match):
        nonlocal changes
        indent = match.group(1)
        type_name = match.group(2)
        var_name = match.group(3)
        method_call = match.group(4)
        finally_body = match.group(5)
        changes += 1
        return (
            f"{indent}try {{\n"
            f"{indent}    return {method_call};\n"
            f"{indent}}} finally {{\n"
            f"{finally_body}"
            f"{indent}}}\n"
        )

    content = pattern.sub(replace_try_return, content)

    if content != original:
        print(f"  ItemContainer: applied {changes} try-finally-return transforms")
        with open(src_path, 'w') as f:
            f.write(content)
    else:
        print("  ItemContainer: no try-finally-return changes applied")

    return changes


# ============================================================================
# Main - Dry run mode by default
# ============================================================================

def main():
    dry_run = "--apply" not in sys.argv

    base = "/home/jaidaken/optizomb/build-41/Decompiled-src/source"

    login_queue_path = os.path.join(base, "zombie/network/LoginQueue.java")
    hashmap_path = os.path.join(base, "zombie/core/Collections/ZomboidHashMap.java")
    item_container_path = os.path.join(base, "zombie/inventory/ItemContainer.java")

    if dry_run:
        print("DRY RUN MODE - analyzing patterns without modifying files")
        print("Use --apply to actually modify files")
        print()

    print("=" * 70)
    print("1. zombie/network/LoginQueue")
    print("=" * 70)
    print("Pattern: Field 's_loginQueue' should be 'LoginQueue' (original name)")
    print("The decompiler renamed it to avoid ambiguity with the class name.")
    print("Every GETSTATIC/PUTSTATIC references LoginQueue.LoginQueue in original,")
    print("but LoginQueue.s_loginQueue in recompiled output.")
    print()
    if not dry_run:
        fix_login_queue(login_queue_path)
    else:
        with open(login_queue_path) as f:
            count = f.read().count('s_loginQueue')
        print(f"  Would replace {count} occurrences of 's_loginQueue' -> 'LoginQueue'")
    print(f"  Expected: 9 methods fixed (all mismatched methods)")
    print()

    print("=" * 70)
    print("2. zombie/core/Collections/ZomboidHashMap")
    print("=" * 70)
    print("Pattern A (6 methods, diff=+3 each): Redundant `var` declarations")
    print("  Decompiler emits: var object1 = entry.key;")
    print("  This creates: GETFIELD key -> ASTORE vN (extra)")
    print("  Then: GETFIELD key -> ... (re-reads the field)")
    print("  Original: GETFIELD key is read once, value reused from local")
    print("  Fix: Remove the var declaration, inline entry.key")
    print()
    print("Pattern B (transfer method, diff=+3): while(true)+break vs do-while")
    print("  while(true){...if(x==null)break;} -> ASTORE/ALOAD/IFNULL/GOTO (4 insns)")
    print("  do{...}while(x!=null);            -> IFNONNULL (1 insn)")
    print("  Fix: Rewrite as do-while loop")
    print()
    print("Pattern C (2 methods): GUARD_CLAUSE_INVERSION - instanceof pattern")
    print("  if(!(x instanceof Y y)) requires decompiler-level fix")
    print()
    print("Pattern D (clone): TRY_CATCH_DIFF - try-catch metadata ordering")
    print("  Requires decompiler-level fix")
    print()
    if not dry_run:
        fix_zomboid_hashmap_var(hashmap_path)
        fix_zomboid_hashmap_transfer(hashmap_path)
    else:
        with open(hashmap_path) as f:
            content = f.read()
        var_count = len(re.findall(r'var \w+ = [\w.]+\.key;', content))
        has_while_true = 'while (true)' in content
        print(f"  Would remove {var_count} var declarations (affects 6 methods)")
        print(f"  Would convert while(true)+break to do-while: {'yes' if has_while_true else 'no'}")
    print(f"  Expected: up to 7 methods fixed (6 var + 1 transfer)")
    print(f"  Remaining unfixable at source level: 2 (instanceof inversion + try-catch)")
    print()

    print("=" * 70)
    print("3. zombie/inventory/ItemContainer")
    print("=" * 70)
    print("Pattern: try-finally with return variable creates 2 extra insns")
    print("  Decompiler emits:")
    print("    InventoryItem item;")
    print("    try { item = this.getBest(...); } finally { ... }")
    print("    return item;   // <-- this generates ALOAD + ARETURN = 2 extra insns")
    print("  Original bytecode has return inside the try block (no separate return).")
    print("  Fix: try { return this.getBest(...); } finally { ... }")
    print()
    if not dry_run:
        fix_item_container_try_return(item_container_path)
    else:
        with open(item_container_path) as f:
            content = f.read()
        pattern = re.compile(
            r'InventoryItem \w+;\n\s+try \{',
            re.MULTILINE
        )
        matches = pattern.findall(content)
        print(f"  Found {len(matches)} try-finally-return patterns to transform")
    print(f"  Expected: 10 methods fixed")
    print()

    print("=" * 70)
    print("SUMMARY")
    print("=" * 70)
    print(f"  LoginQueue:      9 methods fixable via field rename")
    print(f"  ZomboidHashMap:  7 methods fixable (6 var + 1 loop), 2 unfixable")
    print(f"  ItemContainer:  10 methods fixable via try-return restructure")
    print(f"  Total:          26 methods potentially fixed")
    if dry_run:
        print()
        print("Run with --apply to modify the source files.")


if __name__ == "__main__":
    main()
