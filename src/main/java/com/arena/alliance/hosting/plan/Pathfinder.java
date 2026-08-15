package com.arena.alliance.hosting.plan;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GridMath;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 共享障碍记忆上的有界 BFS 寻路：返回从 from 迈向 to 的第一步方向。
 * 未知格按可通行处理（记忆之外的世界乐观探索）；搜索超界时退化为贪心绕障。
 */
public final class Pathfinder {

    private static final String[] DIRECTIONS = {"UP", "DOWN", "LEFT", "RIGHT"};
    private static final long[][] DELTAS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    private static final int MAX_NODES = 4096;
    private static final int MAX_RADIUS = 48;

    private Pathfinder() {
    }

    /**
     * @param blockedExtra 本 Tick 已被己方单位预定/占用的格（软避让，减少 MOVE_CONTESTED）
     * @return 方向字符串；已到达或完全无路时返回 null
     */
    public static String nextStep(Cell from, Cell to, Set<Cell> obstacles, Set<Cell> blockedExtra) {
        if (from == null || to == null || from.equals(to)) {
            return null;
        }
        String bfs = bfsStep(from, to, obstacles, blockedExtra);
        if (bfs != null) {
            return bfs;
        }
        return greedyStep(from, to, obstacles, blockedExtra);
    }

    private static String bfsStep(Cell from, Cell to, Set<Cell> obstacles, Set<Cell> blocked) {
        if (GridMath.manhattan(from, to) > MAX_RADIUS) {
            return null;
        }
        Map<Cell, Cell> parent = new HashMap<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        parent.put(from, from);
        queue.add(from);
        Cell reached = null;
        while (!queue.isEmpty() && parent.size() < MAX_NODES) {
            Cell cur = queue.poll();
            if (cur.equals(to)) {
                reached = cur;
                break;
            }
            for (long[] d : DELTAS) {
                Cell next = GridMath.offset(cur, d[0], d[1]).orElse(null);
                if (next == null) {
                    continue;
                }
                if (parent.containsKey(next) || obstacles.contains(next)) {
                    continue;
                }
                // 目标格本身即使被软占用也允许作为终点（如资源格上有单位路过）
                if (!next.equals(to) && blocked.contains(next)) {
                    continue;
                }
                if (GridMath.manhattan(next, from) > MAX_RADIUS) {
                    continue;
                }
                parent.put(next, cur);
                queue.add(next);
            }
        }
        if (reached == null) {
            return null;
        }
        Cell step = reached;
        while (!parent.get(step).equals(from)) {
            step = parent.get(step);
        }
        return directionOf(from, step);
    }

    private static String greedyStep(Cell from, Cell to, Set<Cell> obstacles, Set<Cell> blocked) {
        int xSign = Long.compare(to.x(), from.x());
        int ySign = Long.compare(to.y(), from.y());
        boolean xPrimary = GridMath.axisDistance(to.x(), from.x())
                >= GridMath.axisDistance(to.y(), from.y());
        String primary = xPrimary
                ? (xSign > 0 ? "RIGHT" : "LEFT")
                : (ySign > 0 ? "DOWN" : "UP");
        String secondary = xPrimary
                ? (ySign > 0 ? "DOWN" : (ySign < 0 ? "UP" : null))
                : (xSign > 0 ? "RIGHT" : (xSign < 0 ? "LEFT" : null));
        for (String dir : new String[]{primary, secondary}) {
            if (dir == null) {
                continue;
            }
            Cell next = from.move(dir);
            if (!next.equals(from) && !obstacles.contains(next) && !blocked.contains(next)) {
                return dir;
            }
        }
        return null;
    }

    private static String directionOf(Cell from, Cell to) {
        for (int i = 0; i < DELTAS.length; i++) {
            if (GridMath.offset(from, DELTAS[i][0], DELTAS[i][1]).filter(to::equals).isPresent()) {
                return DIRECTIONS[i];
            }
        }
        return null;
    }
}
