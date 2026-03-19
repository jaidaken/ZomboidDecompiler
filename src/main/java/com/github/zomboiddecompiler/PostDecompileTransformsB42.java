package com.github.zomboiddecompiler;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build 42-specific post-decompilation source transforms.
 * Split from PostDecompileTransforms to keep B41 and B42 concerns separate.
 */
public final class PostDecompileTransformsB42 {

    private PostDecompileTransformsB42() {}

    /**
     * Apply all B42-specific transforms to the given Java source content.
     * @param content the decompiled source
     */
    public static String apply(String content) {
        content = fixB42PolygonalMap2FindPath(content);
        content = fixB42SpecificErrors(content);
        return content;
    }

    // ========================================================================
    // B42 PolygonalMap2.findPath semaphore variable finally duplication
    // ========================================================================
    // Same Vineflower bug as b41, but b42 uses PZMath.fastfloor(), +32 Z offset,
    // fixPathZ(), vgNode.release(), DebugOptions smooth check, etc.

    private static String fixB42PolygonalMap2FindPath(String content) {
        if (!content.contains("class PolygonalMap2")) return content;
        if (!content.contains("VF: Semaphore variable")) return content;

        String methodSig = "    private boolean findPath(PathFindRequest request, boolean render) {";
        int methodStart = content.indexOf(methodSig);
        if (methodStart < 0) return content;

        // Find method body end by brace counting
        int braceStart = content.indexOf('{', methodStart);
        if (braceStart < 0) return content;
        int depth = 0;
        int methodEnd = -1;
        for (int i = braceStart; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    methodEnd = i + 1;
                    break;
                }
            }
        }
        if (methodEnd < 0) return content;

        String replacement = B42_POLYGONAL_MAP2_FIND_PATH_REWRITE;
        return content.substring(0, methodStart) + replacement + content.substring(methodEnd);
    }

    private static final String B42_POLYGONAL_MAP2_FIND_PATH_REWRITE =
        "    private boolean findPath(PathFindRequest request, boolean render) {\n" +
        "        float requestStartZ = request.startZ + 32.0F;\n" +
        "        float requestTargetZ = request.targetZ + 32.0F;\n" +
        "        int flags = 16;\n" +
        "        if (!(request.mover instanceof IsoZombie)) {\n" +
        "            flags |= 4;\n" +
        "        }\n" +
        "\n" +
        "        if (PZMath.fastfloor(requestStartZ) == PZMath.fastfloor(requestTargetZ)\n" +
        "            && !this.lcc.isNotClear(this, request.startX, request.startY, request.targetX, request.targetY, PZMath.fastfloor(requestStartZ), flags)) {\n" +
        "            request.path.addNode(request.startX, request.startY, request.startZ);\n" +
        "            request.path.addNode(request.targetX, request.targetY, request.targetZ);\n" +
        "            if (render) {\n" +
        "                for (VisibilityGraph vg : this.graphs) {\n" +
        "                    vg.render();\n" +
        "                }\n" +
        "            }\n" +
        "\n" +
        "            return true;\n" +
        "        } else {\n" +
        "            this.astar.init(this.graphs, this.squareToNode);\n" +
        "            this.astar.knownBlockedEdges.clear();\n" +
        "\n" +
        "            for (int i = 0; i < request.knownBlockedEdges.size(); i++) {\n" +
        "                KnownBlockedEdges kbe = request.knownBlockedEdges.get(i);\n" +
        "                Square square1 = this.getSquare(kbe.x, kbe.y, kbe.z);\n" +
        "                if (square1 != null) {\n" +
        "                    this.astar.knownBlockedEdges.put(square1.id, kbe);\n" +
        "                }\n" +
        "            }\n" +
        "\n" +
        "            VisibilityGraph removeStart = null;\n" +
        "            VisibilityGraph removeGoal = null;\n" +
        "            SearchNode startNode = null;\n" +
        "            SearchNode goalNode = null;\n" +
        "            boolean adjustStart = false;\n" +
        "            boolean adjustGoal = false;\n" +
        "\n" +
        "            try {\n" +
        "                int adjusted;\n" +
        "                Square ix = this.getSquare(\n" +
        "                    PZMath.fastfloor(request.startX), PZMath.fastfloor(request.startY), PZMath.fastfloor(requestStartZ)\n" +
        "                );\n" +
        "                if (ix != null && !ix.isReallySolid()) {\n" +
        "                    if (ix.has(504)) {\n" +
        "                        startNode = this.astar.getSearchNode(ix);\n" +
        "                    } else {\n" +
        "                        VisibilityGraph vg = this.astar.getVisGraphForSquare(ix);\n" +
        "                        if (vg != null) {\n" +
        "                            if (!vg.created) {\n" +
        "                                vg.create();\n" +
        "                            }\n" +
        "\n" +
        "                            Node vgNode = null;\n" +
        "                            adjusted = vg.getPointOutsideObstacles(\n" +
        "                                request.startX, request.startY, requestStartZ, this.adjustStartData\n" +
        "                            );\n" +
        "                            if (adjusted == -1) {\n" +
        "                                return false;\n" +
        "                            }\n" +
        "\n" +
        "                            if (adjusted == 1) {\n" +
        "                                adjustStart = true;\n" +
        "                                vgNode = this.adjustStartData.node;\n" +
        "                                if (this.adjustStartData.isNodeNew) {\n" +
        "                                    removeStart = vg;\n" +
        "                                }\n" +
        "                            }\n" +
        "\n" +
        "                            if (vgNode == null) {\n" +
        "                                vgNode = Node.alloc().init(request.startX, request.startY, PZMath.fastfloor(requestStartZ));\n" +
        "                                vg.addNode(vgNode);\n" +
        "                                removeStart = vg;\n" +
        "                            }\n" +
        "\n" +
        "                            startNode = this.astar.getSearchNode(vgNode);\n" +
        "                        }\n" +
        "                    }\n" +
        "\n" +
        "                    if (startNode == null) {\n" +
        "                        startNode = this.astar.getSearchNode(ix);\n" +
        "                    }\n" +
        "\n" +
        "                    if (this.getChunkFromSquarePos(PZMath.fastfloor(request.targetX), PZMath.fastfloor(request.targetY)) == null) {\n" +
        "                        goalNode = this.astar.getSearchNode(PZMath.fastfloor(request.targetX), PZMath.fastfloor(request.targetY));\n" +
        "                    } else {\n" +
        "                        ix = this.getSquare(\n" +
        "                            PZMath.fastfloor(request.targetX), PZMath.fastfloor(request.targetY), PZMath.fastfloor(requestTargetZ)\n" +
        "                        );\n" +
        "                        if (ix == null || ix.isReallySolid()) {\n" +
        "                            return false;\n" +
        "                        }\n" +
        "\n" +
        "                        if ((PZMath.fastfloor(request.startX) != PZMath.fastfloor(request.targetX)\n" +
        "                                || PZMath.fastfloor(request.startY) != PZMath.fastfloor(request.targetY)\n" +
        "                                || PZMath.fastfloor(request.startZ) != PZMath.fastfloor(request.targetZ))\n" +
        "                            && this.isBlockedInAllDirections(\n" +
        "                                PZMath.fastfloor(request.targetX),\n" +
        "                                PZMath.fastfloor(request.targetY),\n" +
        "                                PZMath.fastfloor(requestTargetZ)\n" +
        "                            )) {\n" +
        "                            return false;\n" +
        "                        }\n" +
        "\n" +
        "                        if (ix.has(504)) {\n" +
        "                            goalNode = this.astar.getSearchNode(ix);\n" +
        "                        } else {\n" +
        "                            VisibilityGraph vgx = this.astar.getVisGraphForSquare(ix);\n" +
        "                            if (vgx != null) {\n" +
        "                                if (!vgx.created) {\n" +
        "                                    vgx.create();\n" +
        "                                }\n" +
        "\n" +
        "                                Node vgNodex = null;\n" +
        "                                adjusted = vgx.getPointOutsideObstacles(\n" +
        "                                    request.targetX, request.targetY, requestTargetZ, this.adjustGoalData\n" +
        "                                );\n" +
        "                                if (adjusted == -1) {\n" +
        "                                    return false;\n" +
        "                                }\n" +
        "\n" +
        "                                if (adjusted == 1) {\n" +
        "                                    adjustGoal = true;\n" +
        "                                    vgNodex = this.adjustGoalData.node;\n" +
        "                                    if (this.adjustGoalData.isNodeNew) {\n" +
        "                                        removeGoal = vgx;\n" +
        "                                    }\n" +
        "                                }\n" +
        "\n" +
        "                                if (vgNodex == null) {\n" +
        "                                    vgNodex = Node.alloc().init(request.targetX, request.targetY, PZMath.fastfloor(requestTargetZ));\n" +
        "                                    vgx.addNode(vgNodex);\n" +
        "                                    removeGoal = vgx;\n" +
        "                                }\n" +
        "\n" +
        "                                goalNode = this.astar.getSearchNode(vgNodex);\n" +
        "                            } else {\n" +
        "                                for (int ixx = 0; ixx < this.graphs.size(); ixx++) {\n" +
        "                                    VisibilityGraph graphI = this.graphs.get(ixx);\n" +
        "                                    if (graphI.contains(ix, 1)) {\n" +
        "                                        Node outsideNode = this.getPointOutsideObjects(ix, request.targetX, request.targetY);\n" +
        "                                        graphI.addNode(outsideNode);\n" +
        "                                        if (outsideNode.x != request.targetX || outsideNode.y != request.targetY) {\n" +
        "                                            adjustGoal = true;\n" +
        "                                            this.adjustGoalData.isNodeNew = false;\n" +
        "                                        }\n" +
        "\n" +
        "                                        removeGoal = graphI;\n" +
        "                                        goalNode = this.astar.getSearchNode(outsideNode);\n" +
        "                                        break;\n" +
        "                                    }\n" +
        "                                }\n" +
        "                            }\n" +
        "                        }\n" +
        "\n" +
        "                        if (goalNode == null) {\n" +
        "                            goalNode = this.astar.getSearchNode(ix);\n" +
        "                        }\n" +
        "                    }\n" +
        "\n" +
        "                    ArrayList<ISearchNode> path = this.astar.shortestPath(request, startNode, goalNode);\n" +
        "                    if (path != null) {\n" +
        "                        if (path.size() == 1) {\n" +
        "                            request.path.addNode(startNode);\n" +
        "                            if (!adjustGoal\n" +
        "                                && goalNode.square != null\n" +
        "                                && goalNode.square.x + 0.5F != request.targetX\n" +
        "                                && goalNode.square.y + 0.5F != request.targetY) {\n" +
        "                                request.path.addNode(request.targetX, request.targetY, requestTargetZ, 0);\n" +
        "                            } else {\n" +
        "                                request.path.addNode(goalNode);\n" +
        "                            }\n" +
        "\n" +
        "                            this.fixPathZ(request.path);\n" +
        "                            return true;\n" +
        "                        }\n" +
        "\n" +
        "                        this.cleanPath(path, request, adjustStart, adjustGoal, goalNode);\n" +
        "                        if (DebugOptions.instance.pathfindSmoothPlayerPath.getValue()\n" +
        "                            && request.mover instanceof IsoPlayer isoPlayer\n" +
        "                            && !isoPlayer.isNPC()) {\n" +
        "                            this.smoothPath(request.path);\n" +
        "                        }\n" +
        "\n" +
        "                        this.fixPathZ(request.path);\n" +
        "                        return true;\n" +
        "                    }\n" +
        "\n" +
        "                    return false;\n" +
        "                }\n" +
        "            } finally {\n" +
        "                if (render) {\n" +
        "                    for (VisibilityGraph vg : this.graphs) {\n" +
        "                        vg.render();\n" +
        "                    }\n" +
        "                }\n" +
        "\n" +
        "                if (removeStart != null) {\n" +
        "                    removeStart.removeNode(startNode.vgNode);\n" +
        "                    startNode.vgNode.release();\n" +
        "                }\n" +
        "\n" +
        "                if (removeGoal != null) {\n" +
        "                    removeGoal.removeNode(goalNode.vgNode);\n" +
        "                    goalNode.vgNode.release();\n" +
        "                }\n" +
        "\n" +
        "                for (int ix = 0; ix < this.astar.searchNodes.size(); ix++) {\n" +
        "                    this.astar.searchNodes.get(ix).release();\n" +
        "                }\n" +
        "\n" +
        "                if (adjustStart && this.adjustStartData.isNodeNew) {\n" +
        "                    for (int ix = 0; ix < this.adjustStartData.node.edges.size(); ix++) {\n" +
        "                        Edge edge = this.adjustStartData.node.edges.get(ix);\n" +
        "                        edge.obstacle.unsplit(this.adjustStartData.node, edge.edgeRing);\n" +
        "                    }\n" +
        "\n" +
        "                    this.adjustStartData.graph.edges.remove(this.adjustStartData.newEdge);\n" +
        "                }\n" +
        "\n" +
        "                if (adjustGoal && this.adjustGoalData.isNodeNew) {\n" +
        "                    for (int ix = 0; ix < this.adjustGoalData.node.edges.size(); ix++) {\n" +
        "                        Edge edge = this.adjustGoalData.node.edges.get(ix);\n" +
        "                        edge.obstacle.unsplit(this.adjustGoalData.node, edge.edgeRing);\n" +
        "                    }\n" +
        "\n" +
        "                    this.adjustGoalData.graph.edges.remove(this.adjustGoalData.newEdge);\n" +
        "                }\n" +
        "            }\n" +
        "\n" +
        "            return false;\n" +
        "        }\n" +
        "    }";

    // ========================================================================
    // Build 42-specific fixes
    // ========================================================================

    private static String fixB42SpecificErrors(String content) {
        // MainScreenState: Core.getInstance(.getVersion()) — stray dot before getVersion()
        if (content.contains("class MainScreenState")) {
            content = content.replace(
                    "Core.getInstance(.getVersion())",
                    "Core.getInstance().getVersion()"
            );
        }

        // ================================================================
        // 1. ICallback generics — unchecked cast needed in Invokers, Stacks,
        //    Consumers, Predicates, Comparators
        // ================================================================
        content = fixB42ICallbackGenerics(content);

        // ================================================================
        // 2. "variable already defined" — redeclared variables in same scope
        // ================================================================
        content = fixB42VariableAlreadyDefined(content);

        // ================================================================
        // 3. Enum generics — (Enum[]) needs cast to (E[]) in for-each
        // ================================================================
        content = fixB42EnumGenericsCast(content);

        // ================================================================
        // 4. byte/int to boolean confusion
        // ================================================================
        content = fixB42IntBooleanFields(content);

        // ================================================================
        // 5. File-specific fixes for remaining errors
        // ================================================================
        content = fixB42FileSpecificErrors(content);

        // Switch expressions missing default branch
        content = content.replaceAll(
                "(case \\w+ -> [^;]+;\\s*\\n)(\\s*\\} \\*)",
                "$1            default -> throw new IllegalStateException();\n$2");

        // Vineflower bug: yield before if makes code unreachable in switch expressions.
        // Pattern: yield X;\n  if (...) { ... } — the yield should be the fallback AFTER the if block.
        // General fix: find "yield EXPR;\n<ws>if (" and move the yield after the closing }.
        content = fixYieldBeforeIf(content);

        // AnimalZoneState: ZoneState is private but referenced by public STATE field
        if (content.contains("class AnimalZoneState")) {
            content = content.replace(
                    "private abstract static class ZoneState",
                    "public abstract static class ZoneState");
        }

        // Unreachable catch: CloneNotSupportedException never thrown by super.clone()
        // when the class implements Cloneable. Widen to Exception.
        content = content.replace(
                "catch (CloneNotSupportedException",
                "catch (Exception");

        return content;
    }

    // ========================================================================
    // B42 Helper: ICallback generics — add unchecked cast on assignment
    // ========================================================================
    // In Invokers, Stacks, Consumers, Predicates, Comparators the pattern is:
    //   item.fieldName = paramName;
    // where item is Pool<...<Object,...>> and paramName has generic type params.
    // Fix: item.fieldName = (ICallback)paramName; (raw cast suppresses generics)

    private static String fixB42ICallbackGenerics(String content) {
        if (!content.contains("class Invokers")
                && !content.contains("class Stacks")
                && !content.contains("class Consumers")
                && !content.contains("class Predicates")
                && !content.contains("class Comparators")) {
            return content;
        }

        // Pattern: item.invoker = consumer;  ->  item.invoker = (ICallback)consumer;
        // Pattern: item.callback = callback;  ->  item.callback = (ICallback)callback;
        // Pattern: item.consumer = consumer;  ->  item.consumer = (ICallback)consumer;
        // Pattern: item.predicate = predicate;  ->  item.predicate = (ICallback)predicate;
        // Pattern: item.comparator = comparator;  ->  item.comparator = (ICallback)comparator;
        content = content.replaceAll(
                "(\\s*item\\.(invoker|callback|consumer|predicate|comparator)) = (\\w+);",
                "$1 = (ICallback)$3;"
        );

        return content;
    }

    // ========================================================================
    // B42 Helper: Fix "variable already defined" errors
    // ========================================================================
    // When Vineflower decompiles switch-case or large methods, it redeclares
    // variables. Fix by removing the type from subsequent declarations.

    private static String fixB42VariableAlreadyDefined(String content) {
        // IsoFeedingTrough: instanceof pattern var 'food' conflicts with earlier 'food'
        if (content.contains("class IsoFeedingTrough")) {
            // Rename pattern var and its use within the same line's block
            content = content.replace(
                    "item instanceof DrainableComboItem food)",
                    "item instanceof DrainableComboItem drainableFood)"
            );
            content = content.replace("result += food.getCurrentUses()", "result += drainableFood.getCurrentUses()");
        }

        // BaseCraftingLogic bestMatch redeclaration is fixed in fixB42FileSpecificErrors.

        // AddCoopPlayer: cellx and chunkMap redeclared in update()
        if (content.contains("class AddCoopPlayer")) {
            content = fixB42RemoveRedeclType(content,
                    "IsoCell cellx = IsoWorld.instance.currentCell;",
                    "cellx = IsoWorld.instance.currentCell;",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "IsoChunkMap chunkMap = cellx.chunkMap[this.player.playerIndex];",
                    "chunkMap = cellx.chunkMap[this.player.playerIndex];",
                    1);
        }

        // ChecksumPacket: bbw redeclared in parseServer() — 2nd and 3rd occurrences
        if (content.contains("class ChecksumPacket")) {
            content = fixB42RemoveRedeclType(content,
                    "ByteBufferWriter bbw = connection.startPacket();",
                    "bbw = connection.startPacket();",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "ByteBufferWriter bbw = connection.startPacket();",
                    "bbw = connection.startPacket();",
                    2);
        }

        // CombatManager: lower redeclared in getBodyPart()
        if (content.contains("class CombatManager")) {
            content = fixB42RemoveRedeclType(content,
                    "boolean lower = Rand.Next(2) == 0;",
                    "lower = Rand.Next(2) == 0;",
                    2);
        }

        // VehicleScript shape redeclaration is fixed in fixB42FileSpecificErrors.

        return content;
    }

    /**
     * Remove the type from the Nth occurrence of a variable declaration.
     * @param content source
     * @param fullDecl the full declaration to find (e.g. "int x = 5;")
     * @param assignment the assignment without type (e.g. "x = 5;")
     * @param occurrence which occurrence to fix (1-based; 2 = second occurrence)
     */
    private static String fixB42RemoveRedeclType(String content, String fullDecl, String assignment, int occurrence) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(fullDecl, idx)) >= 0) {
            count++;
            if (count == occurrence) {
                content = content.substring(0, idx)
                        + assignment
                        + content.substring(idx + fullDecl.length());
                break;
            }
            idx += fullDecl.length();
        }
        return content;
    }

    /**
     * Rename the Nth occurrence of an instanceof pattern variable and all uses
     * within its if-block scope.
     */
    private static String fixB42RenameInstanceofPatternVar(String content,
            String instanceofExpr, String oldVar, String newVar, int occurrence) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(instanceofExpr, idx)) >= 0) {
            count++;
            if (count == occurrence) {
                // Replace the pattern variable in the instanceof expression
                String newExpr = instanceofExpr.replace(" " + oldVar, " " + newVar);
                content = content.substring(0, idx) + newExpr + content.substring(idx + instanceofExpr.length());

                // Find the scope (the if-block following this instanceof)
                int braceStart = content.indexOf("{", idx + newExpr.length());
                if (braceStart >= 0) {
                    int braceDepth = 1;
                    int scopeEnd = braceStart + 1;
                    while (scopeEnd < content.length() && braceDepth > 0) {
                        char c = content.charAt(scopeEnd);
                        if (c == '{') braceDepth++;
                        else if (c == '}') braceDepth--;
                        scopeEnd++;
                    }
                    // Replace all occurrences of oldVar within this scope
                    String scopeContent = content.substring(braceStart, scopeEnd);
                    scopeContent = scopeContent.replaceAll("\\b" + Pattern.quote(oldVar) + "\\b", newVar);
                    content = content.substring(0, braceStart) + scopeContent + content.substring(scopeEnd);
                }
                break;
            }
            idx += instanceofExpr.length();
        }
        return content;
    }

    // ========================================================================
    // B42 Helper: Fix Enum generics cast — (Enum[]) to (E[]) with unchecked
    // ========================================================================

    private static String fixB42EnumGenericsCast(String content) {
        // Pattern: for (E e : (Enum[])this.elementType.getEnumConstants())
        // Fix: cast to E[] instead: for (E e : (E[])this.elementType.getEnumConstants())
        // This pattern appears in EnumBitStore, AttributeType, PZArrayUtil

        // General pattern: (Enum[])expr.getEnumConstants() -> (E[])expr.getEnumConstants()
        // where E is the for-each loop variable type
        // More specifically: for (SomeType var : (Enum[])... -> for (SomeType var : (SomeType[])...
        // and: for (SomeType var : (java.lang.Enum[])... -> for (SomeType var : (SomeType[])...
        content = content.replaceAll(
                "for \\((\\w+) (\\w+) : \\((?:java\\.lang\\.)?Enum\\[\\]\\)",
                "for ($1 $2 : ($1[])"
        );

        // EnumBitStore inner class: EnumBitStoreIterator has its own E that shadows outer E
        // Fix the inner class to not redeclare E
        if (content.contains("class EnumBitStore")) {
            // Fix: private class EnumBitStoreIterator<E extends Enum<E> & IOEnum>
            // -> private class EnumBitStoreIterator (remove the type parameter)
            content = content.replace(
                    "private class EnumBitStoreIterator<E extends Enum<E> & IOEnum> implements Iterator<E>",
                    "private class EnumBitStoreIterator implements Iterator<E>"
            );

            // Fix: EnumBitStore<E>.EnumBitStoreIterator<E> -> EnumBitStoreIterator
            content = content.replaceAll(
                    "EnumBitStore<E>\\.EnumBitStoreIterator<E>",
                    "EnumBitStoreIterator"
            );
            // Fix: new EnumBitStore.EnumBitStoreIterator<>() -> new EnumBitStoreIterator()
            content = content.replace(
                    "new EnumBitStore.EnumBitStoreIterator<>()",
                    "new EnumBitStoreIterator()"
            );
        }

        // EnumStringObj: "!(o instanceof EnumStringObj<E> other)" uses reifiable generic
        // Fix: remove the generic parameter from the instanceof
        if (content.contains("class EnumStringObj")) {
            content = content.replace(
                    "!(o instanceof EnumStringObj<E> other)",
                    "!(o instanceof EnumStringObj<?> other)"
            );
        }

        return content;
    }

    // ========================================================================
    // B42 Helper: Fix int/byte assigned to boolean fields
    // ========================================================================

    private static String fixB42IntBooleanFields(String content) {
        // IsoObject: isOutlineHighlight, isOutlineHlAttached, isOutlineHlBlink are byte fields
        // but used in boolean return contexts. The methods return boolean but field is byte.
        // Fix: add != 0 comparison
        if (content.contains("class IsoObject ")) {
            // return this.isOutlineHighlight;  ->  return this.isOutlineHighlight != 0;
            content = content.replaceAll(
                    "return this\\.(isOutlineHighlight|isOutlineHlAttached|isOutlineHlBlink);",
                    "return this.$1 != 0;"
            );
            // if (this.isOutlineHighlight) {  ->  if (this.isOutlineHighlight != 0) {
            content = content.replaceAll(
                    "if \\(this\\.(isOutlineHighlight|isOutlineHlAttached|isOutlineHlBlink)\\)",
                    "if (this.$1 != 0)"
            );
        }

        // VisibilityGraph: f.n = 1; should be f.n = true; (ClusterOutline fields are boolean)
        if (content.contains("class VisibilityGraph")) {
            content = content.replace("f.n = 1;", "f.n = true;");
        }

        // ClusterOutlineGrid: f1.w = 1; should be f1.w = true;
        if (content.contains("class ClusterOutlineGrid")) {
            content = content.replace("f1.w = 1;", "f1.w = true;");
        }

        // FileSystemImpl: priority = (boolean)(16 - this.inProgress.size());
        // should be priority = 16 - this.inProgress.size(); with int type
        if (content.contains("class FileSystemImpl")) {
            content = content.replace(
                    "priority = (boolean)(16 - this.inProgress.size());",
                    "priority = 16 - this.inProgress.size();"
            );
            // Also fix the declaration: boolean priority -> int priority
            content = content.replaceAll(
                    "(\\s*)boolean priority(\\s*[;=])",
                    "$1int priority$2"
            );
            // Fix boolean literal initial value
            content = content.replace("int priority = true;", "int priority = 1;");
            content = content.replace("int priority = false;", "int priority = 0;");
        }

        // IsoWorld: isPlayerAlive = (boolean)PZMath.fastfloor(...)
        //           bLoadCharacter = (boolean)PZMath.fastfloor(...)
        // These should be int assignments. The variables are declared as boolean
        // but actually hold int values (floor coordinates).
        if (content.contains("class IsoWorld ")) {
            content = content.replace(
                    "isPlayerAlive = (boolean)PZMath.fastfloor(IsoPlayer.getInstance().getX());",
                    "isPlayerAlive = PZMath.fastfloor(IsoPlayer.getInstance().getX());"
            );
            content = content.replace(
                    "bLoadCharacter = (boolean)PZMath.fastfloor(IsoPlayer.getInstance().getY());",
                    "bLoadCharacter = PZMath.fastfloor(IsoPlayer.getInstance().getY());"
            );
            // Fix the variable types from boolean to int
            content = content.replaceAll(
                    "(\\s*)boolean isPlayerAlive(\\s*[;=])",
                    "$1int isPlayerAlive$2"
            );
            content = content.replaceAll(
                    "(\\s*)boolean bLoadCharacter(\\s*[;=])",
                    "$1int bLoadCharacter$2"
            );
            // Fix boolean literal assignments that should now be int
            content = content.replace("int isPlayerAlive = false;", "int isPlayerAlive = 0;");
            content = content.replace("isPlayerAlive = true;", "isPlayerAlive = 1;");
            // PlayerDBHelper.isPlayerAlive returns boolean, need ternary to convert to int
            content = content.replace(
                    "isPlayerAlive = PlayerDBHelper.isPlayerAlive(ZomboidFileSystem.instance.getCurrentSaveDir(), 1);",
                    "isPlayerAlive = PlayerDBHelper.isPlayerAlive(ZomboidFileSystem.instance.getCurrentSaveDir(), 1) ? 1 : 0;"
            );
            content = content.replace("int bLoadCharacter = false;", "int bLoadCharacter = 0;");
            content = content.replace("bLoadCharacter = true;", "bLoadCharacter = 1;");
            content = content.replace("bLoadCharacter = false;", "bLoadCharacter = 0;");
            // Fix boolean usage in if conditions
            content = content.replace("if (isPlayerAlive)", "if (isPlayerAlive != 0)");
            content = content.replace("if (bLoadCharacter ", "if (bLoadCharacter != 0 ");
        }

        return content;
    }

    // ========================================================================
    // B42 Helper: File-specific fixes for remaining errors
    // ========================================================================

    private static String fixB42FileSpecificErrors(String content) {
        // UIElement: rawget("Type") returns Object, needs (String) cast
        if (content.contains("class UIElement ")) {
            content = content.replace(
                    "String type = this.table.rawget(\"Type\");",
                    "String type = (String)this.table.rawget(\"Type\");"
            );
        }

        // ImagePyramid: Comparator.comparingInt with raw lambda cast to Object
        // ((Object)o).requestNumber -> ((ImagePyramid.PyramidTexture)o).requestNumber
        if (content.contains("class ImagePyramid")) {
            content = content.replace(
                    "((Object)o).requestNumber",
                    "((ImagePyramid.PyramidTexture)o).requestNumber"
            );
        }

        // ShaderBufferData: same pattern with offset field
        if (content.contains("class ShaderBufferData")) {
            content = content.replace(
                    "((Object)a).offset",
                    "((ShaderParameter)a).offset"
            );
        }

        // FBORenderCell: raw lambda comparators accessing fields on Object
        if (content.contains("class FBORenderCell")) {
            // World inventory objects sort: o1.xoff, o1.yoff on IsoWorldInventoryObject
            content = content.replaceAll(
                    "(o[12])\\.xoff",
                    "((IsoWorldInventoryObject)$1).xoff"
            );
            content = content.replaceAll(
                    "(o[12])\\.yoff",
                    "((IsoWorldInventoryObject)$1).yoff"
            );
            // lightingUpdateCounter sort
            content = content.replace(
                    "((Object)a).lightingUpdateCounter",
                    "((IsoChunk)a).lightingUpdateCounter"
            );
            // Grid square sort: o1.x, o1.y, o2.x, o2.y on IsoGridSquare
            // Need to be careful: only match in the sort lambda context
            content = content.replaceAll(
                    "(int i[12] = )(o[12])\\.x \\+ (o[12])\\.y",
                    "$1((IsoGridSquare)$2).x + ((IsoGridSquare)$3).y"
            );
        }

        // FBORenderObjectPicker: raw lambda comparator accessing .square on ClickObject
        if (content.contains("class FBORenderObjectPicker")) {
            content = content.replaceAll(
                    "(o[12])\\.square\\.z",
                    "((IsoObjectPicker.ClickObject)$1).square.z"
            );
            content = content.replace(
                    "compareRenderLayer(o1, o2)",
                    "compareRenderLayer((IsoObjectPicker.ClickObject)o1, (IsoObjectPicker.ClickObject)o2)"
            );
            content = content.replace(
                    "compareSquare(o1, o2)",
                    "compareSquare((IsoObjectPicker.ClickObject)o1, (IsoObjectPicker.ClickObject)o2)"
            );
        }

        // AnimalZone, IsoMannequin (MannequinZone), VehicleZone:
        // (String)s.rawget("...") — variable 's' not in scope, should be 'properties'
        if (content.contains("class AnimalZone")) {
            content = content.replace(
                    "(String)s.rawget(\"AnimalType\")",
                    "(String)properties.rawget(\"AnimalType\")"
            );
        }

        if (content.contains("class IsoMannequin")) {
            content = content.replace("(String)s.rawget(\"Direction\")", "(String)properties.rawget(\"Direction\")");
            content = content.replace("(String)s.rawget(\"Outfit\")", "(String)properties.rawget(\"Outfit\")");
            content = content.replace("(String)s.rawget(\"Script\")", "(String)properties.rawget(\"Script\")");
            content = content.replace("(String)s.rawget(\"Skin\")", "(String)properties.rawget(\"Skin\")");
            content = content.replace("(String)s.rawget(\"Pose\")", "(String)properties.rawget(\"Pose\")");
        }

        if (content.contains("class VehicleZone")) {
            content = content.replace(
                    "(String)s.rawget(\"Direction\")",
                    "(String)properties.rawget(\"Direction\")"
            );
        }

        // StateMachine: lambda parameter subState inferred as Object, needs cast
        if (content.contains("class StateMachine")) {
            content = content.replace(
                    "(subState, lOwner, lLayer, lTrack, lEvent) -> {\n            if (!subState.isEmpty()) {\n                subState.state.animEvent",
                    "(subState, lOwner, lLayer, lTrack, lEvent) -> {\n            if (!((StateMachine.SubstateSlot)subState).isEmpty()) {\n                ((StateMachine.SubstateSlot)subState).state.animEvent"
            );
        }

        // Pool: Pool<PO> cannot be converted to Pool<IPooledObject>
        // Fix: add raw cast
        if (content.contains("class Pool ") || content.contains("class Pool<")) {
            content = content.replace(
                    "newObj.setPool(new Pool.PoolReference(this, poolStacks));",
                    "newObj.setPool(new Pool.PoolReference((Pool)this, poolStacks));"
            );
        }

        // HandWeapon: text is String but later cast to (Double)text
        // The rawget returns Object, not String. Fix the specific lines.
        if (content.contains("class HandWeapon ")) {
            content = content.replace(
                    "f = (float)((Double)text).doubleValue();",
                    "f = (float)((Double)(Object)text).doubleValue();"
            );
        }

        // IsoAnimal: @Override on methods that don't override parent
        if (content.contains("class IsoAnimal ")) {
            content = content.replace(
                    "@Override\n    public void updateStress()",
                    "public void updateStress()"
            );
            content = content.replace(
                    "@Override\n    public void initializeStates()",
                    "public void initializeStates()"
            );
        }

        // TileGeometryFile: (String)block.getValue("points") — Value not String
        if (content.contains("class TileGeometryFile")) {
            content = content.replace(
                    "value = (String)block.getValue(\"points\");",
                    "value = block.getValue(\"points\");"
            );
        }

        // LoadingQueueUI: 0.4F passed where Double expected in DrawTextureScaledColor
        if (content.contains("class LoadingQueueUI")) {
            content = content.replace("0.4F, 0.4F, 0.4F, 1.0);", "(double)0.4F, (double)0.4F, (double)0.4F, 1.0);");
        }

        // RecipeCodeOnCreate: getConsumedItems/getInputItems/getCreatedItems return List<InventoryItem>
        // but assigned to subtype. Need casts.
        if (content.contains("class RecipeCodeOnCreate")) {
            content = content.replace(
                    "Food head = getConsumedItems(data, ItemTag.ANIMAL_HEAD).getFirst();",
                    "Food head = (Food)getConsumedItems(data, ItemTag.ANIMAL_HEAD).getFirst();"
            );
            content = content.replace(
                    "Clothing item = getConsumedItems(data, ItemTag.PICK_ARAMID_THREAD).getFirst();",
                    "Clothing item = (Clothing)getConsumedItems(data, ItemTag.PICK_ARAMID_THREAD).getFirst();"
            );
            content = content.replace(
                    "Key sourceKey = getInputItems(data, ItemTag.BUILDING_KEY).getFirst();",
                    "Key sourceKey = (Key)getInputItems(data, ItemTag.BUILDING_KEY).getFirst();"
            );
            content = content.replace(
                    "Food macaroni = getCreatedItems(data, ItemTag.PASTA).getFirst();",
                    "Food macaroni = (Food)getCreatedItems(data, ItemTag.PASTA).getFirst();"
            );
            // Multi-line getConsumedItems with ItemKey params returning DrainableComboItem
            content = content.replace(
                    "DrainableComboItem lantern = getConsumedItems(",
                    "DrainableComboItem lantern = (DrainableComboItem)getConsumedItems("
            );
        }

        // EditVehicleState: Collectors.joining returns Object instead of String
        // .collect(Collectors.joining(", ")) returns Object because stream is raw
        if (content.contains("class EditVehicleState")) {
            content = content.replace(
                    "String collect = var10000.<CharSequence>map(xva$0 -> \"%s\".formatted(xva$0)).collect(Collectors.joining(\", \"));",
                    "String collect = (String)var10000.<CharSequence>map(xva$0 -> \"%s\".formatted(xva$0)).collect(Collectors.joining(\", \"));"
            );
        }

        // PrimitiveFloatList: forEach ambiguity — method ref action::accept is ambiguous
        if (content.contains("class PrimitiveFloatList")) {
            content = content.replace(
                    "this.forEach(action::accept);",
                    "this.forEach((FloatConsumer)action::accept);"
            );
        }

        // ZombieDeleteOnClientPacket: raw ArrayList cast needs type in for-each
        if (content.contains("class ZombieDeleteOnClientPacket")) {
            content = content.replace(
                    "for (NetworkZombiePacker.DeletedZombie dz : (ArrayList)values[1])",
                    "for (NetworkZombiePacker.DeletedZombie dz : (ArrayList<NetworkZombiePacker.DeletedZombie>)values[1])"
            );
        }

        // ClothingWetnessPacket: raw List cast needs type in for-each
        if (content.contains("class ClothingWetnessPacket")) {
            content = content.replace(
                    "for (InventoryItem item : (List)values[1])",
                    "for (InventoryItem item : (List<InventoryItem>)values[1])"
            );
        }

        // RemoveInventoryItemFromContainerPacket: raw ArrayList cast
        if (content.contains("class RemoveInventoryItemFromContainerPacket")) {
            content = content.replace(
                    "for (InventoryItem item : (ArrayList)values[1])",
                    "for (InventoryItem item : (ArrayList<InventoryItem>)values[1])"
            );
        }

        // GameServer: (String)ServerOptions.instance.isPublic.getValue() — getValue returns Boolean
        if (content.contains("class GameServer ")) {
            content = content.replace(
                    "String tags = (String)ServerOptions.instance.isPublic.getValue() ? \"\" : \"hidden\";",
                    "String tags = ServerOptions.instance.isPublic.getValue() ? \"\" : \"hidden\";"
            );
        }

        // IsoLot: ambiguous get() call — remove Integer.valueOf wrapper
        if (content.contains("class IsoLot ")) {
            content = content.replace(
                    "return get(mapFiles, cX, cY, wX, Integer.valueOf(wY), ch);",
                    "return get(mapFiles, Integer.valueOf(cX), Integer.valueOf(cY), Integer.valueOf(wX), Integer.valueOf(wY), ch);"
            );
        }

        // Select: QuickSelect<?> wildcard causes T[] incompatibility
        if (content.contains("class Select ")) {
            content = content.replace(
                    "private QuickSelect<?> quickSelect;",
                    "private QuickSelect quickSelect;"
            );
        }

        // XuiScript: (XuiScript.XuiVar<T, C>) uses undefined T, C
        if (content.contains("class XuiScript")) {
            content = content.replace(
                    "(XuiScript.XuiVar<T, C>)style.getVar(",
                    "(XuiScript.XuiVar)style.getVar("
            );
            content = content.replace(
                    "(XuiScript.XuiVar<T, C>)defaultStyle.getVar(",
                    "(XuiScript.XuiVar)defaultStyle.getVar("
            );
        }

        // FirearmPanel: stream collect inference — toCollection(ArrayList::new) can't infer
        // generic types because the stream element is Object. Cast the map result.
        if (content.contains("class FirearmPanel")) {
            content = content.replace(
                    ".map(i -> InventoryItemFactory.CreateItem(i.getFullName()))",
                    ".<WeaponPart>map(i -> (WeaponPart)InventoryItemFactory.CreateItem(i.getFullName()))"
            );
        }

        // AnimalZones: subState.getClass() on inaccessible type
        if (content.contains("class AnimalZones")) {
            content = content.replace(
                    "subState.getClass().getSimpleName()",
                    "((Object)subState).getClass().getSimpleName()"
            );
        }

        // PZArrayUtil enum generic cast is handled by the general fixB42EnumGenericsCast regex

        // ================================================================
        // UI3DScene: many "variable already defined" errors across fromLua methods
        // ================================================================
        if (content.contains("class UI3DScene ")) {
            // fromLua0: ArrayList<String> names redeclared in "getObjectNames" case
            // First is in "getGeometryNames", second in "getObjectNames" — same switch scope
            content = fixB42RemoveRedeclType(content,
                    "ArrayList<String> names = new ArrayList<>();",
                    "names = new ArrayList<>();",
                    2);

            // fromLua2: int i = 0; at line 992, then for (int i = 0; ...) at line 1085
            // The for-loop redeclares 'i' — remove the type from the for-loop
            content = content.replace(
                    "for (int i = 0; i < keyframes.length; i++)",
                    "for (i = 0; i < keyframes.length; i++)");

            // fromLua2: sceneCharacter instanceof pattern at line 1353 conflicts with
            // earlier SceneCharacter sceneCharacter declaration at line 1064.
            // Rename the instanceof pattern variable to _sceneCharacter.
            content = fixB42RenameInstanceofPatternVar(content,
                    "sceneObject instanceof UI3DScene.SceneCharacter sceneCharacter",
                    "sceneCharacter", "_sceneCharacter", 1);

            // fromLua2: modID, tileName, sprite, spriteGrid, spriteGridIndex redeclared
            // in "subtractSpriteGridPixels" case — first declarations are in "copyGeometryFromSpriteGrid"
            content = fixB42RemoveRedeclType(content,
                    "String modID = (String)arg0;",
                    "modID = (String)arg0;",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "String tileName = (String)arg1;",
                    "tileName = (String)arg1;",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "IsoSprite sprite = IsoSpriteManager.instance.getSprite(tileName);",
                    "sprite = IsoSpriteManager.instance.getSprite(tileName);",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "IsoSpriteGrid spriteGrid = sprite.getSpriteGrid();",
                    "spriteGrid = sprite.getSpriteGrid();",
                    2);
            content = fixB42RemoveRedeclType(content,
                    "int spriteGridIndex = spriteGrid.getSpriteIndex(sprite);",
                    "spriteGridIndex = spriteGrid.getSpriteIndex(sprite);",
                    2);

            // fromLua3: Matrix4f transform redeclared in "setAttachmentToOrigin" case
            // String is unique in the file — occurrence=1 targets the only match
            content = fixB42RemoveRedeclType(content,
                    "Matrix4f transform = sceneModel.getGlobalTransform(allocMatrix4f());",
                    "transform = sceneModel.getGlobalTransform(allocMatrix4f());",
                    1);
            // fromLua3: Quaternionf rotation redeclared — identical string at lines 1585 and 1628
            content = fixB42RemoveRedeclType(content,
                    "Quaternionf rotation = transform.getUnnormalizedRotation(allocQuaternionf());",
                    "rotation = transform.getUnnormalizedRotation(allocQuaternionf());",
                    2);

            // fromLua3: sceneModel instanceof pattern at line 1655 conflicts with
            // SceneModel sceneModel declaration at line 1611.
            // Convert instanceof pattern to instanceof + cast to avoid field name
            // collision (this.originBone.sceneModel shares the name).
            content = content.replace(
                    "if (sceneObject instanceof UI3DScene.SceneModel sceneModel) {\n"
                            + "                            this.gizmoParent = sceneModel;\n"
                            + "                            this.originBone.character = null;\n"
                            + "                            this.originBone.sceneModel = sceneModel;\n"
                            + "                            this.originBone.boneName = (String)arg2;\n"
                            + "                            this.gizmoOrigin = this.originBone;\n"
                            + "                            this.gizmoChild = null;\n"
                            + "                        }",
                    "if (sceneObject instanceof UI3DScene.SceneModel) {\n"
                            + "                            UI3DScene.SceneModel sceneModel2 = (UI3DScene.SceneModel)sceneObject;\n"
                            + "                            this.gizmoParent = sceneModel2;\n"
                            + "                            this.originBone.character = null;\n"
                            + "                            this.originBone.sceneModel = sceneModel2;\n"
                            + "                            this.originBone.boneName = (String)arg2;\n"
                            + "                            this.gizmoOrigin = this.originBone;\n"
                            + "                            this.gizmoChild = null;\n"
                            + "                        }");

            // fromLua6: byte col = -1 conflicts with int col earlier in same switch
            // Only one occurrence of "byte col = -1;" — replace directly
            content = content.replace("byte col = -1;", "col = -1;");
        }

        // ================================================================
        // ModelLoader: 6 for-loop redeclarations of 'n' in loadTxt()
        // The first 'n' is declared as standalone "int n = 0;" (VertexBuffer case),
        // then 6 for-loops in subsequent switch cases all redeclare 'int n'.
        // Replace ALL "for (int n = " with "for (n = " in this file since every
        // for-loop 'n' conflicts with the standalone declaration.
        // ================================================================
        if (content.contains("class ModelLoader")) {
            content = content.replace("for (int n = 0; n < numElements; n++)",
                    "for (n = 0; n < numElements; n++)");
            content = content.replace("for (int n = 0; n < numBones; n++)",
                    "for (n = 0; n < numBones; n++)");
            content = content.replace("for (int n = 0; n < nFrames; n++)",
                    "for (n = 0; n < nFrames; n++)");
        }

        // ================================================================
        // IsoMannequin: String i conflicts with for (int i) in syncModel()
        // ================================================================
        if (content.contains("class IsoMannequin")) {
            // Rename "String i = this.modelScriptName;" to avoid conflict with loop var i
            content = content.replace(
                    "String i = this.modelScriptName;\n        switch (i)",
                    "String modelScriptSwitch = this.modelScriptName;\n        switch (modelScriptSwitch)");
        }

        // ================================================================
        // SpriteModel: byte modelAttachment conflicts with ModelAttachment modelAttachment
        // in both parseStandardDoor() and parsePairDoor()
        // ================================================================
        if (content.contains("class SpriteModel")) {
            // Rename the byte switch variable to edgeIndex in both methods.
            // The byte variable is used for edge direction (n=1, w=0) then
            // conflicts with ModelAttachment modelAttachment later.
            content = content.replace("byte modelAttachment = -1;", "byte edgeIndex = -1;");
            content = content.replace("modelAttachment = 1;", "edgeIndex = 1;");
            content = content.replace("modelAttachment = 0;", "edgeIndex = 0;");
            content = content.replace(
                    "String meshName = switch (modelAttachment)",
                    "String meshName = switch (edgeIndex)");
        }

        // ================================================================
        // VehicleScript: String shape conflicts with PhysicsShape shape in LoadPhysicsShape()
        // ================================================================
        if (content.contains("class VehicleScript ")) {
            // String shape is only used in declaration and switch expression
            content = content.replace(
                    "String shape = block.id;\n        int type;\n        switch (shape)",
                    "String shapeName = block.id;\n        int type;\n        switch (shapeName)");
        }

        // ================================================================
        // BaseCraftingLogic: bestMatch redeclared in switch case OutputName
        // (3rd overall occurrence; first is in Tags block, 2nd in InputName, 3rd in OutputName)
        // InputName and OutputName are in the same switch scope so 3rd conflicts with 2nd.
        // ================================================================
        if (content.contains("class BaseCraftingLogic")) {
            content = fixB42RemoveRedeclType(content,
                    "BaseCraftingLogic.FilterStringMatchType bestMatch = BaseCraftingLogic.FilterStringMatchType.NONE;",
                    "bestMatch = BaseCraftingLogic.FilterStringMatchType.NONE;",
                    3);
        }

        // ================================================================
        // ClothingWetness: clothing instanceof pattern variable escapes scope
        // 'clothing' is declared in "if (item instanceof Clothing clothing)" but
        // used outside that if-block. Replace escaped usage with cast on 'item'.
        // ================================================================
        if (content.contains("class ClothingWetness ")) {
            content = content.replace(
                    "clothing.setWetness(clothing.getWetness() + delta);",
                    "((Clothing)item).setWetness(((Clothing)item).getWetness() + delta);");
        }

        return content;
    }

    /**
     * Fix Vineflower bug where yield appears before an if-block in switch expressions,
     * making the if-block unreachable. Moves the yield to after the if-block as the
     * default fallback.
     */
    private static String fixYieldBeforeIf(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int i = 0;
        boolean modified = false;

        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("yield ") && trimmed.endsWith(";")
                    && i + 1 < lines.length && lines[i + 1].trim().startsWith("if (")) {
                String yieldLine = lines[i];
                String yieldIndent = yieldLine.substring(0, yieldLine.indexOf("yield"));
                int ifStart = i + 1;
                int depth = 0;
                int ifEnd = ifStart;
                for (int j = ifStart; j < lines.length; j++) {
                    for (char c : lines[j].toCharArray()) {
                        if (c == '{') depth++;
                        else if (c == '}') depth--;
                    }
                    if (depth <= 0) {
                        ifEnd = j;
                        break;
                    }
                }
                for (int j = ifStart; j <= ifEnd; j++) {
                    result.append(lines[j]).append('\n');
                }
                result.append(yieldIndent).append(trimmed).append('\n');
                i = ifEnd + 1;
                modified = true;
            } else {
                result.append(lines[i]).append('\n');
                i++;
            }
        }

        if (modified && result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return modified ? result.toString() : content;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
