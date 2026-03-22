# Truncated Methods Investigation

## Problem

Three methods have their tail code silently dropped during decompilation. The code exists in the original bytecode, survives CFG construction and statement structuring, but is not rendered in the final Java output. Zero errors are reported - the code is just missing.

### Affected Methods

1. **VoiceManager.InitVMClient()** - Drops `this.thread = new VoiceManager$4(this); thread.setName("VoiceManagerClient"); thread.start();` (thread creation at method end)
2. **GameLoadingState.enter()** - Drops `loader = new Thread(ThreadGroups.Workers, new GameLoadingState$1(this)); loader.setName("GameLoadingThread"); loader.start();` (loader thread creation at method end)
3. **IsoWindow(IsoCell, IsoGridSquare, IsoSprite, boolean)** - Drops a `switch(this.type.ordinal()) { case 1: Health=MaxHealth=50; case 2: Health=MaxHealth=100; }` (WindowType switch at constructor end)

### Common Pattern

All three methods have:
- Complex control flow earlier in the method (if-else chains, try-catch)
- An if-else block where one branch sets "disabled" state and the other does the main work
- The dropped code comes AFTER the if-else block
- The dropped code creates objects (anonymous classes, Thread) or uses switch statements

## Root Cause: Rendering Suppression

The code IS present in the statement tree throughout the entire decompilation pipeline. It is suppressed at render time by `SequenceStatement.toJava()` in the Vineflower fork.

### The Suppression Code

File: `/home/jaidaken/optizomb/vineflower/src/org/jetbrains/java/decompiler/modules/decompiler/stats/SequenceStatement.java`, lines 119-125:

```java
// RTF: if this child always exits via a non-regular path (continue/break/
// return/throw), subsequent children are bytecode-level dead code that
// javac would reject as "unreachable statement". Stop rendering.
if (DecompilerContext.isRoundtripFidelity() && i < stats.size() - 1
    && endsWithNonRegularExit(st)) {
  break;
}
```

This was added to prevent javac "unreachable statement" compilation errors. When a statement unconditionally exits (return, throw, break, continue), any code after it is unreachable and javac rejects it. The suppression hides this dead code.

### Why It's Wrong for These 3 Methods

The `endsWithNonRegularExit()` function (lines 164-227) checks if a statement unconditionally exits. It has several checks:

1. **Edge check** (line 166-172): Statement has exactly one non-regular explicit successor edge
2. **Switch check** (line 181-203): Switch with default where all cases exit
3. **Sequence recursion** (line 206-211): For SequenceStatements, recursively checks the last child
4. **ExitExprent check** (line 216-221): Last exprent is a return/throw

For the affected methods, the structure is:
```
SequenceStatement [
  IfStatement (if-else) [
    if-body: SequenceStatement [... main code ...]
    else-body: SequenceStatement [... disabled code ... DebugLog.debugln("Disabled")]
  ]
  BasicBlockStatement [thread creation code]  ← THIS IS SUPPRESSED
]
```

The IfStatement's else-body ends with a return (or the if-body does). The `endsWithNonRegularExit` recursion walks into the IfStatement → into the SequenceStatement body → finds the last child ends with a return → returns true. This suppresses the BasicBlockStatement that follows.

But the IfStatement has TWO branches. Only ONE exits. The other falls through. So the code after the IfStatement IS reachable from the branch that doesn't exit.

### The Real Fix Needed

The `endsWithNonRegularExit` function needs to understand that an IfStatement with an if-else only "ends with a non-regular exit" if BOTH branches exit, not just one. Currently the recursion into SequenceStatement → last child can reach inside one branch of an if-else and incorrectly conclude the whole thing exits.

However, simply removing the ExitExprent check or the SequenceStatement recursion causes 28 "unreachable statement" compilation errors in other methods where the suppression is CORRECT (genuinely unreachable dead code).

### What Was Tried

1. **Removing the ExitExprent check entirely**: Recovered 43 methods (+226 EXACT) but introduced 28 "unreachable statement" errors. Net: more methods recovered than broken, but can't ship with compilation errors.

2. **Adding if-else parent guard**: Only suppress SequenceStatement recursion when the parent is NOT an if-else. Didn't fix the target methods because the suppression path doesn't always go through an if-else parent.

3. **Adding regular-successor-edge guard**: Only recurse into SequenceStatement children when the sequence has no regular outgoing edges. Same 28 errors remained.

### Verified Pipeline Trace

Instrumentation at key points in MethodProcessor.codeToJava() confirmed:
- **CFG construction**: All blocks present (22 blocks, 128 instructions for VoiceManager)
- **After parseGraph**: 36 statements (all code present)
- **After FinallyProcessor**: 36 statements (nothing removed)
- **After simplifyStackVars**: 22 statements (IfHelper merged nested ifs, reducing count - but code still present)
- **Main loop start**: 22 statements (code still present)
- **Main loop end**: 22 statements (code still present)
- **Rendered Java**: Thread creation code MISSING (suppressed by endsWithNonRegularExit)

The code survives the entire pipeline. It's only hidden during rendering.

### Suggested Fix Approach

The `endsWithNonRegularExit` function needs to be rewritten to properly handle branching statements. Specifically:

For an **IfStatement** to "end with a non-regular exit":
- If IFTYPE_IF (no else): the if-body must exit AND the fall-through must exit (which it can't, since there's no else). So IFTYPE_IF never unconditionally exits on its own - only if the IfStatement has no regular successor edges.
- If IFTYPE_IFELSE: BOTH the if-body AND the else-body must end with non-regular exits.

For a **SequenceStatement** to "end with a non-regular exit":
- Its LAST child must end with a non-regular exit.
- This recursion is correct as-is.

The current code doesn't check IfStatement branches - it just recurses into the last child of sequences, which can accidentally enter one branch of an if-else.

The tricky part: 28 methods NEED the current aggressive suppression to avoid "unreachable statement" errors. Any fix must maintain suppression for those while recovering the 3 (or more) falsely suppressed methods.

### Key Files

- `/home/jaidaken/optizomb/vineflower/src/org/jetbrains/java/decompiler/modules/decompiler/stats/SequenceStatement.java` - `endsWithNonRegularExit()` at line 164, `toJava()` at line 93
- `/home/jaidaken/optizomb/vineflower/src/org/jetbrains/java/decompiler/main/rels/MethodProcessor.java` - Pipeline orchestration
- `/home/jaidaken/optizomb/vineflower/src/org/jetbrains/java/decompiler/modules/decompiler/stats/IfStatement.java` - IfStatement structure

### Original Bytecode References

```
# VoiceManager.InitVMClient - thread creation at offsets 246-287:
247: invokestatic System.currentTimeMillis
250: putfield timeLast
253: aload_0
254: iconst_0
255: putfield bQuit
258: aload_0
259: new VoiceManager$4
262: dup
263: aload_0
264: invokespecial VoiceManager$4.<init>
267: putfield thread
270-277: thread.setName("VoiceManagerClient")
280-284: thread.start()
287: return

# GameLoadingState.enter - loader creation at offsets 589-644
# IsoWindow.<init> - WindowType switch at offset 468-530
```
