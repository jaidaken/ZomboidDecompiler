package com.github.zomboiddecompiler;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Archived post-decompilation transforms that are no longer active.
 *
 * <p><b>Eliminated (15):</b> These transforms were needed to fix bytecode-matching divergences
 * but have been eliminated by fixes in the Vineflower fork (RTF mode improvements,
 * hasValueOne float comparison, CatchStatement exception widening, etc.).
 * They are preserved here for reference in case of regressions.</p>
 *
 * <p><b>Never needed in B41 (8):</b> These transforms were written proactively but never
 * triggered on any B41 class. They may be useful for B42 or future builds.</p>
 */
public final class PostDecompileTransformsArchive {

    private PostDecompileTransformsArchive() {}

    // ========================================================================
    // ELIMINATED: IsoMovingObject.compareToY float-to-double widening
    // ========================================================================
    // Original bytecode widens floats to double before comparison.
    // Eliminated by Vineflower fork fix.

    static String fixIsoMovingObjectCompareToYWiden(String content) {
        if (!content.contains("class IsoMovingObject ")) return content;
        content = content.replace(
                "if (float0 > float1) {\n" +
                "                return 1;\n" +
                "            } else {\n" +
                "                return float0 < float1 ? -1 : 0;",
                "if ((double)float0 > (double)float1) {\n" +
                "                return 1;\n" +
                "            } else {\n" +
                "                return (double)float0 < (double)float1 ? -1 : 0;");
        return content;
    }

    // ========================================================================
    // ELIMINATED: IsoObject.getNew() synchronized ternary return
    // ========================================================================
    // Old javac emits separate MONITOREXIT+ARETURN per branch; modern javac
    // merges with GOTO to a single MONITOREXIT+ARETURN.

    static String fixIsoObjectGetNewSyncReturn(String content) {
        if (!content.contains("class IsoObject ")) return content;
        return content.replace(
                "    public static IsoObject getNew() {\n" +
                "        synchronized (CellLoader.isoObjectCache) {\n" +
                "            return CellLoader.isoObjectCache.isEmpty() ? new IsoObject() : CellLoader.isoObjectCache.pop();\n" +
                "        }\n" +
                "    }",
                "    public static IsoObject getNew() {\n" +
                "        synchronized (CellLoader.isoObjectCache) {\n" +
                "            if (CellLoader.isoObjectCache.isEmpty()) {\n" +
                "                return new IsoObject();\n" +
                "            }\n" +
                "            return CellLoader.isoObjectCache.pop();\n" +
                "        }\n" +
                "    }");
    }

    // ========================================================================
    // ELIMINATED: MultiStageBuilding$Stage.canBeDone() direct boolean return
    // ========================================================================
    // Original bytecode uses IFNE/ICONST_0/IRETURN/ICONST_1/IRETURN instead
    // of a direct ILOAD+IRETURN for the boolean result.

    static String fixMultiStageBuildingCanBeDoneReturn(String content) {
        if (!content.contains("class MultiStageBuilding")) return content;
        return content.replace(
                "            return boolean0;\n" +
                "        }",
                "            if (!boolean0) {\n" +
                "                return false;\n" +
                "            }\n" +
                "            return true;\n" +
                "        }");
    }

    // ========================================================================
    // ELIMINATED: ServerLOS$LOSThread.shouldWait() synchronized return
    // ========================================================================
    // Original bytecode returns false inside the synchronized block when sizes
    // differ, then returns true after exiting the monitor for the equal case.

    static String fixServerLOSShouldWaitSyncReturn(String content) {
        if (!content.contains("class ServerLOS ")) return content;
        return content.replace(
                "                synchronized (ServerLOS.this.playersMain) {\n" +
                "                    return ServerLOS.this.playersLOS.size() == ServerLOS.this.playersMain.size();\n" +
                "                }",
                "                synchronized (ServerLOS.this.playersMain) {\n" +
                "                    if (ServerLOS.this.playersLOS.size() != ServerLOS.this.playersMain.size()) {\n" +
                "                        return false;\n" +
                "                    }\n" +
                "                }\n" +
                "                return true;");
    }

    // ========================================================================
    // ELIMINATED: spnetwork/ZomboidNetDataPool.get() synchronized ternary
    // ========================================================================
    // Same pattern as IsoObject.getNew().

    static String fixSpNetworkPoolGetSyncReturn(String content) {
        if (!content.contains("package zombie.spnetwork;")) return content;
        if (!content.contains("class ZomboidNetDataPool")) return content;
        return content.replace(
                "    public ZomboidNetData get() {\n" +
                "        synchronized (this.Pool) {\n" +
                "            return this.Pool.isEmpty() ? new ZomboidNetData() : this.Pool.pop();\n" +
                "        }\n" +
                "    }",
                "    public ZomboidNetData get() {\n" +
                "        synchronized (this.Pool) {\n" +
                "            if (this.Pool.isEmpty()) {\n" +
                "                return new ZomboidNetData();\n" +
                "            }\n" +
                "            return this.Pool.pop();\n" +
                "        }\n" +
                "    }");
    }

    // ========================================================================
    // ELIMINATED: ItemContainer try-finally return pattern
    // ========================================================================
    // The decompiler produces var-outside-try; try { var = expr; } finally { ... }; return var;
    // Original bytecode has: try { return expr; } finally { ... }

    private static final String[] ITEM_CONTAINER_TRY_FINALLY_METHODS = {
            "getBestType",
            "getBestTypeRecurse",
            "getBestEval",
            "getBestEvalRecurse",
            "getBestEvalArg",
            "getBestEvalArgRecurse",
            "getBestTypeEval",
            "getBestTypeEvalRecurse",
            "getBestTypeEvalArg",
            "getBestTypeEvalArgRecurse",
    };

    static String fixItemContainerTryFinallyReturn(String content) {
        if (!content.contains("class ItemContainer")) {
            return content;
        }

        for (String methodName : ITEM_CONTAINER_TRY_FINALLY_METHODS) {
            content = fixOneTryFinallyReturn(content, methodName);
        }
        return content;
    }

    private static String fixOneTryFinallyReturn(String content, String methodName) {
        // Find the method
        String methodSig = "public InventoryItem " + methodName + "(";
        int methodStart = content.indexOf(methodSig);
        if (methodStart < 0) {
            return content;
        }

        // Find the method body end by brace counting
        int braceStart = content.indexOf('{', methodStart);
        if (braceStart < 0) {
            return content;
        }
        int depth = 0;
        int methodEnd = -1;
        for (int i = braceStart; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    methodEnd = i + 1;
                    break;
                }
            }
        }
        if (methodEnd < 0) {
            return content;
        }

        String methodBody = content.substring(methodStart, methodEnd);

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "([ \\t]+)InventoryItem (\\w+);\\n" +
                "\\1try \\{\\n" +
                "\\1    \\2 = (this\\.getBest\\w*\\([^)]*\\));\\n" +
                "\\1\\} finally \\{\\n" +
                "((?:\\1    [^\\n]+\\n)+)" +
                "\\1\\}\\n" +
                "\\n" +
                "\\1return \\2;\\n"
        );

        java.util.regex.Matcher matcher = pattern.matcher(methodBody);
        if (!matcher.find()) {
            return content;
        }

        String indent = matcher.group(1);
        String callExpr = matcher.group(3);
        String finallyBody = matcher.group(4);

        String replacement =
                indent + "try {\n" +
                indent + "    return " + callExpr + ";\n" +
                indent + "} finally {\n" +
                finallyBody +
                indent + "}\n";

        String newMethodBody = methodBody.substring(0, matcher.start())
                + replacement
                + methodBody.substring(matcher.end());

        return content.substring(0, methodStart) + newMethodBody + content.substring(methodEnd);
    }

    // ========================================================================
    // ELIMINATED: ActionContext transitionOut check removal
    // ========================================================================
    // Vineflower incorrectly duplicates the transitionOut check from
    // evaluateSubStateTransitions into evaluateCurrentStateTransitions.

    static String fixActionContextTransitionOutCheck(String content) {
        if (!content.contains("class ActionContext ")) return content;
        content = content.replace(
                "            if (actionTransition.passes(this, 0)) {\n" +
                "                if (actionTransition.transitionOut) {\n" +
                "                    break;\n" +
                "                }\n" +
                "\n" +
                "                if (StringUtils.isNullOrWhitespace(actionTransition.transitionTo))",
                "            if (actionTransition.passes(this, 0)) {\n" +
                "                if (StringUtils.isNullOrWhitespace(actionTransition.transitionTo))");
        return content;
    }

    // ========================================================================
    // ELIMINATED: IsoDeadBody.getReanimateDelay switch expression restore
    // ========================================================================
    // The original bytecode has a tableswitch from 1 to 6 where case 1 falls
    // through to default. The decompiler produces a switch expression from 2 to 6.

    static String fixIsoDeadBodyReanimateSwitch(String content) {
        if (!content.contains("class IsoDeadBody ")) return content;
        if (!content.contains("float1 = switch (SandboxOptions.instance.Lore.Reanimate.getValue())")) return content;
        content = content.replace(
                "        float1 = switch (SandboxOptions.instance.Lore.Reanimate.getValue()) {\n" +
                "            case 2 -> 0.008333334F;\n" +
                "            case 3 -> 0.016666668F;\n" +
                "            case 4 -> 12.0F;\n" +
                "            case 5 -> {\n" +
                "                float0 = 48.0F;\n" +
                "                yield 72.0F;\n" +
                "            }\n" +
                "            case 6 -> {\n" +
                "                float0 = 168.0F;\n" +
                "                yield 336.0F;\n" +
                "            }\n" +
                "           default -> throw new IllegalStateException();\n" +
                "        };",
                "        switch (SandboxOptions.instance.Lore.Reanimate.getValue()) {\n" +
                "            case 1:\n" +
                "                break;\n" +
                "            case 2:\n" +
                "                float1 = 0.008333334F;\n" +
                "                break;\n" +
                "            case 3:\n" +
                "                float1 = 0.016666668F;\n" +
                "                break;\n" +
                "            case 4:\n" +
                "                float1 = 12.0F;\n" +
                "                break;\n" +
                "            case 5:\n" +
                "                float0 = 48.0F;\n" +
                "                float1 = 72.0F;\n" +
                "                break;\n" +
                "            case 6:\n" +
                "                float0 = 168.0F;\n" +
                "                float1 = 336.0F;\n" +
                "                break;\n" +
                "        }");
        return content;
    }

    // ========================================================================
    // ELIMINATED: DiskFileDevice$DiskFile.seek switch structure
    // ========================================================================
    // The original bytecode uses an imperative switch that modifies long0 in-place.

    static String fixDiskFileSeekSwitchStructure(String content) {
        if (!content.contains("class DiskFileDevice ")) return content;
        if (!content.contains("this.m_file.seek(switch (fileSeekMode)")) return content;
        content = content.replace(
                "                    this.m_file.seek(switch (fileSeekMode) {\n" +
                "                        case CURRENT -> this.m_file.getFilePointer();\n" +
                "                        case END -> this.m_file.length();\n" +
                "                       default -> throw new IllegalStateException();\n" +
                "                    });\n" +
                "                    return true;",
                "                    switch (fileSeekMode) {\n" +
                "                        case BEGIN:\n" +
                "                            break;\n" +
                "                        case CURRENT:\n" +
                "                            long0 = long0 + this.m_file.getFilePointer();\n" +
                "                            break;\n" +
                "                        case END:\n" +
                "                            long0 = this.m_file.length() + long0;\n" +
                "                            break;\n" +
                "                    }\n" +
                "\n" +
                "                    this.m_file.seek(long0);\n" +
                "                    return true;");
        return content;
    }

    // ========================================================================
    // ELIMINATED: PolygonalMap2.findPath semaphore variable finally duplication
    // ========================================================================
    // Vineflower fails to reconstruct try-finally and falls back to a semaphore
    // variable pattern with 9 copies of the cleanup block.

    static String fixPolygonalMap2FindPath(String content) {
        if (!content.contains("class PolygonalMap2")) return content;
        if (!content.contains("VF: Semaphore variable")) return content;

        String methodSig = "    private boolean findPath(PolygonalMap2.PathFindRequest pathFindRequest, boolean boolean0) {";
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

        String replacement = POLYGONAL_MAP2_FIND_PATH_REWRITE;
        return content.substring(0, methodStart) + replacement + content.substring(methodEnd);
    }

    private static final String POLYGONAL_MAP2_FIND_PATH_REWRITE =
        "    private boolean findPath(PolygonalMap2.PathFindRequest pathFindRequest, boolean boolean0) {\n" +
        "        byte byte0 = 16;\n" +
        "        if (!(pathFindRequest.mover instanceof IsoZombie)) {\n" +
        "            byte0 |= 4;\n" +
        "        }\n" +
        "\n" +
        "        if ((int)pathFindRequest.startZ == (int)pathFindRequest.targetZ\n" +
        "            && !this.lcc\n" +
        "                .isNotClear(\n" +
        "                    this, pathFindRequest.startX, pathFindRequest.startY, pathFindRequest.targetX, pathFindRequest.targetY, (int)pathFindRequest.startZ, byte0\n" +
        "                )) {\n" +
        "            pathFindRequest.path.addNode(pathFindRequest.startX, pathFindRequest.startY, pathFindRequest.startZ);\n" +
        "            pathFindRequest.path.addNode(pathFindRequest.targetX, pathFindRequest.targetY, pathFindRequest.targetZ);\n" +
        "            if (boolean0) {\n" +
        "                for (PolygonalMap2.VisibilityGraph visibilityGraph0 : this.graphs) {\n" +
        "                    visibilityGraph0.render();\n" +
        "                }\n" +
        "            }\n" +
        "\n" +
        "            return true;\n" +
        "        } else {\n" +
        "            this.astar.init(this.graphs, this.squareToNode);\n" +
        "            this.astar.knownBlockedEdges.clear();\n" +
        "\n" +
        "            for (int int0 = 0; int0 < pathFindRequest.knownBlockedEdges.size(); int0++) {\n" +
        "                KnownBlockedEdges knownBlockedEdges = pathFindRequest.knownBlockedEdges.get(int0);\n" +
        "                PolygonalMap2.Square square0 = this.getSquare(knownBlockedEdges.x, knownBlockedEdges.y, knownBlockedEdges.z);\n" +
        "                if (square0 != null) {\n" +
        "                    this.astar.knownBlockedEdges.put(square0.ID, knownBlockedEdges);\n" +
        "                }\n" +
        "            }\n" +
        "\n" +
        "            PolygonalMap2.VisibilityGraph visibilityGraph1 = null;\n" +
        "            PolygonalMap2.VisibilityGraph visibilityGraph2 = null;\n" +
        "            PolygonalMap2.SearchNode searchNode0 = null;\n" +
        "            PolygonalMap2.SearchNode searchNode1 = null;\n" +
        "            boolean boolean1 = false;\n" +
        "            boolean boolean2 = false;\n" +
        "\n" +
        "            try {\n" +
        "                int int1;\n" +
        "                PolygonalMap2.Square square1 = this.getSquare(\n" +
        "                    (int)pathFindRequest.startX, (int)pathFindRequest.startY, (int)pathFindRequest.startZ\n" +
        "                );\n" +
        "                if (square1 != null && !square1.isReallySolid()) {\n" +
        "                    if (square1.has(504)) {\n" +
        "                        searchNode0 = this.astar.getSearchNode(square1);\n" +
        "                    } else {\n" +
        "                        PolygonalMap2.VisibilityGraph visibilityGraph3 = this.astar.getVisGraphForSquare(square1);\n" +
        "                        if (visibilityGraph3 != null) {\n" +
        "                            if (!visibilityGraph3.created) {\n" +
        "                                visibilityGraph3.create();\n" +
        "                            }\n" +
        "\n" +
        "                            PolygonalMap2.Node node0 = null;\n" +
        "                            int1 = visibilityGraph3.getPointOutsideObstacles(\n" +
        "                                pathFindRequest.startX, pathFindRequest.startY, pathFindRequest.startZ, this.adjustStartData\n" +
        "                            );\n" +
        "                            if (int1 == -1) {\n" +
        "                                return false;\n" +
        "                            }\n" +
        "\n" +
        "                            if (int1 == 1) {\n" +
        "                                boolean1 = true;\n" +
        "                                node0 = this.adjustStartData.node;\n" +
        "                                if (this.adjustStartData.isNodeNew) {\n" +
        "                                    visibilityGraph1 = visibilityGraph3;\n" +
        "                                }\n" +
        "                            }\n" +
        "\n" +
        "                            if (node0 == null) {\n" +
        "                                node0 = PolygonalMap2.Node.alloc()\n" +
        "                                    .init(pathFindRequest.startX, pathFindRequest.startY, (int)pathFindRequest.startZ);\n" +
        "                                visibilityGraph3.addNode(node0);\n" +
        "                                visibilityGraph1 = visibilityGraph3;\n" +
        "                            }\n" +
        "\n" +
        "                            searchNode0 = this.astar.getSearchNode(node0);\n" +
        "                        }\n" +
        "                    }\n" +
        "\n" +
        "                    if (searchNode0 == null) {\n" +
        "                        searchNode0 = this.astar.getSearchNode(square1);\n" +
        "                    }\n" +
        "\n" +
        "                    if (!(pathFindRequest.targetX < 0.0F)\n" +
        "                        && !(pathFindRequest.targetY < 0.0F)\n" +
        "                        && this.getChunkFromSquarePos((int)pathFindRequest.targetX, (int)pathFindRequest.targetY) != null) {\n" +
        "                        square1 = this.getSquare(\n" +
        "                            (int)pathFindRequest.targetX, (int)pathFindRequest.targetY, (int)pathFindRequest.targetZ\n" +
        "                        );\n" +
        "                        if (square1 == null || square1.isReallySolid()) {\n" +
        "                            return false;\n" +
        "                        }\n" +
        "\n" +
        "                        if ((\n" +
        "                                (int)pathFindRequest.startX != (int)pathFindRequest.targetX\n" +
        "                                    || (int)pathFindRequest.startY != (int)pathFindRequest.targetY\n" +
        "                                    || (int)pathFindRequest.startZ != (int)pathFindRequest.targetZ\n" +
        "                            )\n" +
        "                            && this.isBlockedInAllDirections(\n" +
        "                                (int)pathFindRequest.targetX, (int)pathFindRequest.targetY, (int)pathFindRequest.targetZ\n" +
        "                            )) {\n" +
        "                            return false;\n" +
        "                        }\n" +
        "\n" +
        "                        if (square1.has(504)) {\n" +
        "                            searchNode1 = this.astar.getSearchNode(square1);\n" +
        "                        } else {\n" +
        "                            PolygonalMap2.VisibilityGraph visibilityGraph4 = this.astar.getVisGraphForSquare(square1);\n" +
        "                            if (visibilityGraph4 != null) {\n" +
        "                                if (!visibilityGraph4.created) {\n" +
        "                                    visibilityGraph4.create();\n" +
        "                                }\n" +
        "\n" +
        "                                PolygonalMap2.Node node1 = null;\n" +
        "                                int1 = visibilityGraph4.getPointOutsideObstacles(\n" +
        "                                    pathFindRequest.targetX, pathFindRequest.targetY, pathFindRequest.targetZ, this.adjustGoalData\n" +
        "                                );\n" +
        "                                if (int1 == -1) {\n" +
        "                                    return false;\n" +
        "                                }\n" +
        "\n" +
        "                                if (int1 == 1) {\n" +
        "                                    boolean2 = true;\n" +
        "                                    node1 = this.adjustGoalData.node;\n" +
        "                                    if (this.adjustGoalData.isNodeNew) {\n" +
        "                                        visibilityGraph2 = visibilityGraph4;\n" +
        "                                    }\n" +
        "                                }\n" +
        "\n" +
        "                                if (node1 == null) {\n" +
        "                                    node1 = PolygonalMap2.Node.alloc()\n" +
        "                                        .init(pathFindRequest.targetX, pathFindRequest.targetY, (int)pathFindRequest.targetZ);\n" +
        "                                    visibilityGraph4.addNode(node1);\n" +
        "                                    visibilityGraph2 = visibilityGraph4;\n" +
        "                                }\n" +
        "\n" +
        "                                searchNode1 = this.astar.getSearchNode(node1);\n" +
        "                            } else {\n" +
        "                                for (int int2 = 0; int2 < this.graphs.size(); int2++) {\n" +
        "                                    PolygonalMap2.VisibilityGraph visibilityGraph5 = this.graphs.get(int2);\n" +
        "                                    if (visibilityGraph5.contains(square1, 1)) {\n" +
        "                                        PolygonalMap2.Node node2 = this.getPointOutsideObjects(\n" +
        "                                            square1, pathFindRequest.targetX, pathFindRequest.targetY\n" +
        "                                        );\n" +
        "                                        visibilityGraph5.addNode(node2);\n" +
        "                                        if (node2.x != pathFindRequest.targetX || node2.y != pathFindRequest.targetY) {\n" +
        "                                            boolean2 = true;\n" +
        "                                            this.adjustGoalData.isNodeNew = false;\n" +
        "                                        }\n" +
        "\n" +
        "                                        visibilityGraph2 = visibilityGraph5;\n" +
        "                                        searchNode1 = this.astar.getSearchNode(node2);\n" +
        "                                        break;\n" +
        "                                    }\n" +
        "                                }\n" +
        "                            }\n" +
        "                        }\n" +
        "\n" +
        "                        if (searchNode1 == null) {\n" +
        "                            searchNode1 = this.astar.getSearchNode(square1);\n" +
        "                        }\n" +
        "                    } else {\n" +
        "                        searchNode1 = this.astar.getSearchNode((int)pathFindRequest.targetX, (int)pathFindRequest.targetY);\n" +
        "                    }\n" +
        "\n" +
        "                    ArrayList arrayList = this.astar.shortestPath(pathFindRequest, searchNode0, searchNode1);\n" +
        "                    if (arrayList != null) {\n" +
        "                        if (arrayList.size() == 1) {\n" +
        "                            pathFindRequest.path.addNode(searchNode0);\n" +
        "                            if (!boolean2\n" +
        "                                && searchNode1.square != null\n" +
        "                                && searchNode1.square.x + 0.5F != pathFindRequest.targetX\n" +
        "                                && searchNode1.square.y + 0.5F != pathFindRequest.targetY) {\n" +
        "                                pathFindRequest.path\n" +
        "                                    .addNode(pathFindRequest.targetX, pathFindRequest.targetY, pathFindRequest.targetZ, 0);\n" +
        "                            } else {\n" +
        "                                pathFindRequest.path.addNode(searchNode1);\n" +
        "                            }\n" +
        "\n" +
        "                            return true;\n" +
        "                        }\n" +
        "\n" +
        "                        this.cleanPath(arrayList, pathFindRequest, boolean1, boolean2, searchNode1);\n" +
        "                        if (pathFindRequest.mover instanceof IsoPlayer && !((IsoPlayer)pathFindRequest.mover).isNPC()) {\n" +
        "                            this.smoothPath(pathFindRequest.path);\n" +
        "                        }\n" +
        "\n" +
        "                        return true;\n" +
        "                    }\n" +
        "\n" +
        "                    return false;\n" +
        "                }\n" +
        "\n" +
        "                return false;\n" +
        "            } finally {\n" +
        "                if (boolean0) {\n" +
        "                    for (PolygonalMap2.VisibilityGraph visibilityGraph6 : this.graphs) {\n" +
        "                        visibilityGraph6.render();\n" +
        "                    }\n" +
        "                }\n" +
        "\n" +
        "                if (visibilityGraph1 != null) {\n" +
        "                    visibilityGraph1.removeNode(searchNode0.vgNode);\n" +
        "                }\n" +
        "\n" +
        "                if (visibilityGraph2 != null) {\n" +
        "                    visibilityGraph2.removeNode(searchNode1.vgNode);\n" +
        "                }\n" +
        "\n" +
        "                for (int int3 = 0; int3 < this.astar.searchNodes.size(); int3++) {\n" +
        "                    this.astar.searchNodes.get(int3).release();\n" +
        "                }\n" +
        "\n" +
        "                if (boolean1 && this.adjustStartData.isNodeNew) {\n" +
        "                    for (int int3 = 0; int3 < this.adjustStartData.node.edges.size(); int3++) {\n" +
        "                        PolygonalMap2.Edge edge0 = this.adjustStartData.node.edges.get(int3);\n" +
        "                        edge0.obstacle.unsplit(this.adjustStartData.node, edge0.edgeRing);\n" +
        "                    }\n" +
        "\n" +
        "                    this.adjustStartData.graph.edges.remove(this.adjustStartData.newEdge);\n" +
        "                }\n" +
        "\n" +
        "                if (boolean2 && this.adjustGoalData.isNodeNew) {\n" +
        "                    for (int int3 = 0; int3 < this.adjustGoalData.node.edges.size(); int3++) {\n" +
        "                        PolygonalMap2.Edge edge0 = this.adjustGoalData.node.edges.get(int3);\n" +
        "                        edge0.obstacle.unsplit(this.adjustGoalData.node, edge0.edgeRing);\n" +
        "                    }\n" +
        "\n" +
        "                    this.adjustGoalData.graph.edges.remove(this.adjustGoalData.newEdge);\n" +
        "                }\n" +
        "            }\n" +
        "        }\n" +
        "    }";

    // ========================================================================
    // NEVER NEEDED IN B41: Logger.log null ambiguity
    // ========================================================================

    static String fixLoggerNullAmbiguity(String content) {
        if (!content.contains(".log(Level.")) return content;
        return content.replace(
                ".log(Level.SEVERE, ",
                ".log(Level.SEVERE, "
        ).replaceAll(
                "\\.log\\(Level\\.(\\w+), ([^,]+), null\\)",
                ".log(Level.$1, $2, (Throwable)null)"
        );
    }

    // ========================================================================
    // NEVER NEEDED IN B41: Raw sort comparators with lambda field access
    // ========================================================================

    static String fixRawSortComparators(String content) {
        content = content.replaceAll(
                "(\\w+)\\.sort\\((\\w+), (\\w+)\\) -> (\\3) - (\\2)\\)",
                "((java.util.List<Integer>)$1).sort(($2, $3) -> ((Integer)$3) - ((Integer)$2))"
        );
        return content;
    }

    // ========================================================================
    // NEVER NEEDED IN B41: java.io.File used as incorrect variable type
    // ========================================================================

    static String fixMistypedJavaIoFile(String content) {
        if (!content.contains("java.io.File")) return content;
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = Pattern.compile("\\bjava\\.io\\.File (\\w+)\\s*[=;,)]").matcher(lines[i]);
            if (!m.find()) continue;
            String varName = m.group(1);
            String vn = Pattern.quote(varName);

            int scopeEnd = Math.min(findScopeEnd(lines, i + 1), lines.length);
            String correctType = null;

            for (int j = i; j < scopeEnd; j++) {
                String line = lines[j];
                if (Pattern.compile("\\b" + vn + "\\s*=\\s*\\w+\\.rawget\\(").matcher(line).find()) {
                    correctType = "Object";
                    break;
                }
                if (Pattern.compile("\\b" + vn + "\\s+instanceof\\s+(?!File\\b)\\w+").matcher(line).find()) {
                    correctType = "Object";
                    break;
                }
                if (line.contains(varName + ".getClass()")) {
                    correctType = "Object";
                    break;
                }
                Matcher nm = Pattern.compile("\\b" + vn + "\\s*=\\s*new (\\w+(?:\\.\\w+)*)\\(").matcher(line);
                if (nm.find()) {
                    String assignedType = nm.group(1);
                    if (!assignedType.equals("File") && !assignedType.startsWith("java.io.File")) {
                        if (assignedType.contains(".")) {
                            correctType = assignedType.substring(0, assignedType.indexOf('.'));
                        } else {
                            correctType = "Object";
                        }
                        break;
                    }
                }
                Matcher cm = Pattern.compile("\\((?!File)(\\w+)\\)" + vn + "\\b").matcher(line);
                if (cm.find()) {
                    correctType = cm.group(1);
                    break;
                }
            }

            if (correctType != null) {
                lines[i] = lines[i].replaceFirst(
                        "java\\.io\\.File(?=\\s+" + vn + ")",
                        correctType
                );
                modified = true;
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // NEVER NEEDED IN B41: String variable assigned non-String type
    // ========================================================================

    static String fixMistypedStringVar(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();
            Matcher m = Pattern.compile("^String (\\w+);$").matcher(stripped);
            if (!m.matches()) continue;
            String varName = m.group(1);

            int scopeEnd = Math.min(findScopeEnd(lines, i + 1), lines.length);
            String vn = Pattern.quote(varName);

            for (int j = i + 1; j < scopeEnd; j++) {
                if (Pattern.compile("\\b" + vn + "\\s*=\\s*new DecimalFormat\\(").matcher(lines[j]).find() ||
                        Pattern.compile("\\b" + vn + "\\s*=\\s*NumberFormat\\.").matcher(lines[j]).find()) {
                    lines[i] = lines[i].replaceFirst("\\bString(?= " + vn + ";)", "NumberFormat");
                    modified = true;
                    break;
                }
                Matcher nm = Pattern.compile("\\b" + vn + "\\s*=\\s*new (\\w+)\\(").matcher(lines[j]);
                if (nm.find() && !nm.group(1).equals("String")) {
                    lines[i] = lines[i].replaceFirst("\\bString(?= " + vn + ";)", nm.group(1));
                    modified = true;
                    break;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // NEVER NEEDED IN B41: boolean to int and int to boolean conversions
    // ========================================================================

    static String fixBooleanIntConversion(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        Set<String> intVars = new HashSet<>();
        for (String line : lines) {
            Matcher m = Pattern.compile("\\bint (\\w+)\\s*[=;,)]").matcher(line);
            while (m.find()) intVars.add(m.group(1));
        }

        for (int i = 0; i < lines.length; i++) {
            Matcher m = Pattern.compile(
                    "^(\\s*)(\\w+) = ([\\w.]+\\.(?:isPlayerAlive|isEmpty|contains|exists|isFile|isDirectory|startsWith|endsWith|equals|matches)\\(.+);$"
            ).matcher(lines[i]);
            if (m.matches() && intVars.contains(m.group(2))) {
                String indent = m.group(1);
                String varName = m.group(2);
                String expr = m.group(3);
                lines[i] = indent + varName + " = " + expr + " ? 1 : 0;";
                modified = true;
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // NEVER NEEDED IN B41: Empty switch expression case
    // ========================================================================

    static String fixEmptySwitchExpressionCase(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;
        boolean inSwitchExpr = false;
        int switchExprStart = -1;
        int switchDepth = 0;

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("switch (") || lines[i].contains("switch(")) {
                boolean isExpression = false;
                for (int peek = i + 1; peek < Math.min(i + 5, lines.length); peek++) {
                    if (lines[peek].trim().startsWith("case ") && lines[peek].contains("->")) {
                        isExpression = true;
                        break;
                    }
                    if (lines[peek].trim().startsWith("case ") && lines[peek].contains(":")) {
                        break;
                    }
                }
                if (isExpression) {
                    inSwitchExpr = true;
                    switchExprStart = i;
                    switchDepth = 0;
                }
            }
            if (inSwitchExpr) {
                switchDepth += countChar(lines[i], '{') - countChar(lines[i], '}');
                if (switchDepth <= 0) {
                    boolean hasDefault = false;
                    for (int j = switchExprStart; j <= i; j++) {
                        if (lines[j].trim().startsWith("default")) {
                            hasDefault = true;
                            break;
                        }
                    }
                    if (!hasDefault) {
                        for (int j = i; j >= switchExprStart; j--) {
                            String trimmed = lines[j].trim();
                            if (trimmed.startsWith("}") && (trimmed.equals("};") || trimmed.equals("});") || trimmed.equals("})"))) {
                                String indent = lines[j].substring(0, lines[j].indexOf("}"));
                                String defaultYield = "default -> throw new IllegalStateException();";
                                lines[j] = indent + "   " + defaultYield + "\n" + lines[j];
                                modified = true;
                                break;
                            }
                        }
                    }
                    inSwitchExpr = false;
                }

                Matcher cm = Pattern.compile("^(\\s*)case .+ -> \\{\\s*$").matcher(lines[i]);
                if (cm.matches() && i + 1 < lines.length && lines[i + 1].trim().equals("}")) {
                    String bodyIndent = cm.group(1) + "   ";
                    lines[i] = lines[i] + "\n" + bodyIndent + "yield null;";
                    modified = true;
                }
            }
        }

        if (!modified) return content;
        return String.join("\n", lines);
    }

    // ========================================================================
    // NEVER NEEDED IN B41: Wrong (String) cast on .get() calls
    // ========================================================================

    private static final Pattern STRING_CAST_GET_DECL = Pattern.compile(
            "^(\\s*)String (\\w+) = \\(String\\)(\\S+\\.get\\(.+\\));$"
    );
    private static final Pattern STRING_CAST_GET_ASSIGN = Pattern.compile(
            "^(\\s*)(\\w+) = \\(String\\)(\\S+\\.get\\(.+\\));$"
    );
    private static final Pattern STRING_VAR_DECL = Pattern.compile(
            "^(\\s*)String (\\w+) = (.+);$"
    );

    static String fixWrongStringCastOnGet(String content) {
        String[] lines = content.split("\n", -1);
        boolean modified = false;

        Set<String> varsWithCastGet = new HashSet<>();
        Map<String, Integer> stringDeclLines = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher declM = STRING_VAR_DECL.matcher(lines[i]);
            if (declM.matches()) {
                stringDeclLines.put(declM.group(2), i);
            }
            Matcher castDeclM = STRING_CAST_GET_DECL.matcher(lines[i]);
            if (castDeclM.matches()) {
                varsWithCastGet.add(castDeclM.group(2));
                continue;
            }
            Matcher castAssignM = STRING_CAST_GET_ASSIGN.matcher(lines[i]);
            if (castAssignM.matches() && stringDeclLines.containsKey(castAssignM.group(2))) {
                varsWithCastGet.add(castAssignM.group(2));
            }
        }

        Set<String> varsToWiden = new HashSet<>();
        for (String varName : varsWithCastGet) {
            Integer declLine = stringDeclLines.get(varName);
            if (declLine == null) continue;
            int scopeEnd = Math.min(findScopeEnd(lines, declLine + 1), lines.length);
            for (int j = declLine + 1; j < scopeEnd; j++) {
                String line = lines[j].stripLeading();
                if (line.contains(varName + " instanceof ")
                        || line.contains("prepareMetatableCall(" + varName)
                        || line.contains("getBinMetaOp(" + varName)
                        || line.contains("this.call(" + varName)
                        || line.contains(".call(" + varName)) {
                    varsToWiden.add(varName);
                    break;
                }
            }
        }

        if (varsToWiden.isEmpty()) return content;

        for (int i = 0; i < lines.length; i++) {
            for (String varName : varsToWiden) {
                Matcher m = STRING_CAST_GET_DECL.matcher(lines[i]);
                if (m.matches() && m.group(2).equals(varName)) {
                    lines[i] = m.group(1) + "Object " + varName + " = " + m.group(3) + ";";
                    modified = true;
                    break;
                }
                Integer declLine = stringDeclLines.get(varName);
                if (declLine != null && declLine == i) {
                    Matcher dm = STRING_VAR_DECL.matcher(lines[i]);
                    if (dm.matches() && dm.group(2).equals(varName)) {
                        lines[i] = dm.group(1) + "Object " + varName + " = " + dm.group(3) + ";";
                        modified = true;
                        break;
                    }
                }
                Matcher m2 = STRING_CAST_GET_ASSIGN.matcher(lines[i]);
                if (m2.matches() && m2.group(2).equals(varName)) {
                    lines[i] = m2.group(1) + varName + " = " + m2.group(3) + ";";
                    modified = true;
                    break;
                }
            }
        }

        return modified ? String.join("\n", lines) : content;
    }

    // ========================================================================
    // NEVER NEEDED IN B41: ShortBuffer.put() missing (short) cast
    // ========================================================================

    private static final Pattern SHORT_BUFFER_PUT_NO_CAST = Pattern.compile(
            "(\\bshortBuffer\\w*\\.put\\()([a-zA-Z_]\\w*)(\\);)"
    );

    static String fixShortBufferPutMissingCast(String content) {
        return SHORT_BUFFER_PUT_NO_CAST.matcher(content)
                .replaceAll("$1(short)$2$3");
    }

    // ========================================================================
    // Helpers (shared with archive transforms)
    // ========================================================================

    private static int findScopeEnd(String[] lines, int startLine) {
        int depth = 0;
        for (int j = startLine; j < lines.length; j++) {
            depth += countChar(lines[j], '{') - countChar(lines[j], '}');
            if (depth < 0) return j;
        }
        return lines.length;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    // ========================================================================
    // ELIMINATED: Vineflower ConstExprent.hasValueOne() exact float comparison
    // ========================================================================
    // These 4 transforms fixed cases where Vineflower collapsed x += 1.1f into
    // x++ because hasValueOne() truncated float to int (1.1f -> intValue() == 1).
    // Fixed by using floatValue()==1.0f / doubleValue()==1.0 instead of intValue()==1.

    static String fixClimbStateFloatIncrement(String content) {
        if (!content.contains("ClimbOverFenceState") && !content.contains("ClimbThroughWindowState")) {
            return content;
        }
        content = content.replace(
                "case S:\n                    float2++;\n                    break;",
                "case S:\n                    float2 += 1.1F;\n                    break;"
        );
        content = content.replace(
                "case E:\n                    float1++;",
                "case E:\n                    float1 += 1.1F;"
        );
        content = content.replace(
                "case S:\n                        float5++;\n                        break;",
                "case S:\n                        float5 += 1.1F;\n                        break;"
        );
        content = content.replace(
                "case E:\n                        float4++;",
                "case E:\n                        float4 += 1.1F;"
        );
        return content;
    }

    static String fixVehicleStorySpawnerAngle(String content) {
        if (!content.contains("class RandomizedVehicleStoryBase ")) return content;
        content = content.replace(
                "vehicleStorySpawner.spawn(floats[0], floats[1], 0.0F, ++float0, this::spawnElement);",
                "float0 += 1.5707964F;\n            vehicleStorySpawner.spawn(floats[0], floats[1], 0.0F, float0, this::spawnElement);");
        return content;
    }

    static String fixAddBloodToMapSubtract(String content) {
        if (!content.contains("class VirtualZombieManager ")) return content;
        content = content.replace(
                "chunk.addBloodSplat(\n" +
                "                        ((IsoGridSquare)object).getX() + --float0, ((IsoGridSquare)object).getY() + --float1, ((IsoGridSquare)object).getZ(), Rand.Next(12) + 8",
                "float0 -= 1.5F;\n" +
                "                    float1 -= 1.5F;\n" +
                "                    chunk.addBloodSplat(\n" +
                "                        ((IsoGridSquare)object).getX() + float0, ((IsoGridSquare)object).getY() + float1, ((IsoGridSquare)object).getZ(), Rand.Next(12) + 8");
        return content;
    }

    static String fixIsoChunkAddCorpsesSubtract(String content) {
        if (!content.contains("class IsoChunk ")) return content;
        content = content.replace(
                "this.addBloodSplat(\n" +
                "                                ((IsoGridSquare)object).getX() + --float1,\n" +
                "                                ((IsoGridSquare)object).getY() + --float2,",
                "float1 -= 1.5F;\n" +
                "                            float2 -= 1.5F;\n" +
                "                            this.addBloodSplat(\n" +
                "                                ((IsoGridSquare)object).getX() + float1,\n" +
                "                                ((IsoGridSquare)object).getY() + float2,");
        return content;
    }

    // ========================================================================
    // ELIMINATED: Vineflower CatchStatement RTF exception widening
    // ========================================================================
    // Vineflower now widens CloneNotSupportedException to Exception in RTF mode
    // when the try body doesn't throw it.

    static String fixUncaughtExceptionInTry(String content) {
        if (content.contains("catch (CloneNotSupportedException")) {
            content = content.replace(
                    "catch (CloneNotSupportedException cloneNotSupportedException)",
                    "catch (Exception cloneNotSupportedException)"
            );
        }
        return content;
    }

    // ========================================================================
    // ELIMINATED: Vineflower AssertProcessor RTF bypass + field rename
    // ========================================================================
    // In RTF mode, AssertProcessor is skipped (no assert keyword conversion).
    // FieldExprent and ClassWriter rename $assertionsDisabled to _assertionsDisabled.

    static String fixAssertionsDisabled(String content) {
        if (!content.contains("$assertionsDisabled")) return content;
        if (content.contains("static final boolean $assertionsDisabled")) {
            content = content.replace("$assertionsDisabled", "_assertionsDisabled");
            return content;
        }
        // Add field declaration + rename
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:public |private |protected )?(?:abstract |final )?class (\\w+)[^{]*\\{"
        ).matcher(content);
        if (!m.find()) return content;
        String className = m.group(1);
        int insertPos = m.end();
        String field = "\n   static final boolean _assertionsDisabled = !" +
                className + ".class.desiredAssertionStatus();\n";
        content = content.substring(0, insertPos) + field + content.substring(insertPos);
        content = content.replace("$assertionsDisabled", "_assertionsDisabled");
        return content;
    }

    private static final java.util.regex.Pattern ASSERT_STMT = java.util.regex.Pattern.compile(
            "^(\\s*)assert (.+);\\s*$"
    );

    static String fixAssertKeywordToExplicit(String content) {
        if (!content.contains("_assertionsDisabled")) return content;
        if (!content.contains("\nassert ") && !content.contains(" assert ")) return content;
        String[] lines = content.split("\n", -1);
        boolean modified = false;
        for (int i = 0; i < lines.length; i++) {
            java.util.regex.Matcher m = ASSERT_STMT.matcher(lines[i]);
            if (m.matches()) {
                String indent = m.group(1);
                String condition = m.group(2);
                lines[i] = indent + "if (!_assertionsDisabled && !(" + condition + ")) { throw new AssertionError(); }";
                modified = true;
            }
        }
        return modified ? String.join("\n", lines) : content;
    }
}
