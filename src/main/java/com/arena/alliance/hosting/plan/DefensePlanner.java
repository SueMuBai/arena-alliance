package com.arena.alliance.hosting.plan;

import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.CombatGeometry;
import com.arena.alliance.game.dto.GridMath;
import com.arena.alliance.game.dto.UnitType;
import com.arena.alliance.hosting.plan.PlanningModels.EnemyView;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 防御微操（纯函数）：合法立即攻击、守位点位、Worker 撤离判断、Core 预撤方向。
 * 原则来自参考 agent：合法立即攻击优先于走位；防御不追击；Core 预撤选远离威胁的可行格。
 */
public final class DefensePlanner {

    /** Worker 遇敌撤离半径 */
    static final int WORKER_FLEE_RADIUS = 6;
    /** 守位屏障：Vanguard 外圈 / Ranger 内圈（参考 agent 3/2 格屏的 M2 简化） */
    static final int VANGUARD_SCREEN = 2;
    static final int RANGER_SCREEN = 1;

    private static final String[] DIRECTIONS = {"UP", "DOWN", "LEFT", "RIGHT"};

    private DefensePlanner() {
    }

    /** 合法立即攻击：Vanguard 扫相邻敌格；Ranger 射直线/对角 1-3 格内最近敌。无则 null */
    public static ObjectNode tryAttack(Cell pos, UnitType type, List<EnemyView> enemies) {
        return tryAttack(pos, type, enemies, Set.of());
    }

    public static ObjectNode tryAttack(Cell pos, UnitType type, List<EnemyView> enemies,
                                       Set<Cell> obstacles) {
        if (type == UnitType.VANGUARD) {
            for (String direction : DIRECTIONS) {
                Cell target = pos.move(direction);
                if (enemies.stream().anyMatch(e -> e.pos().equals(target))) {
                    ObjectNode node = GameJson.MAPPER.createObjectNode();
                    node.put("type", "SWEEP");
                    node.put("direction", direction);
                    return node;
                }
            }
            return null;
        }
        if (type == UnitType.RANGER) {
            return enemies.stream()
                    .filter(e -> CombatGeometry.canShoot(pos, e.pos(), obstacles::contains))
                    .min(Comparator.comparingInt((EnemyView e) -> shotRange(pos, e.pos()))
                            .thenComparing(EnemyView::id))
                    .map(e -> {
                        ObjectNode node = GameJson.MAPPER.createObjectNode();
                        node.put("type", "SHOOT");
                        node.putArray("expected_cell").add(e.pos().x()).add(e.pos().y());
                        return node;
                    })
                    .orElse(null);
        }
        return null;
    }

    /** 射击合法距离：同行/同列/严格对角且 1-3 格；否则 0（不合法） */
    static int shotRange(Cell from, Cell to) {
        long dx;
        long dy;
        try {
            dx = Math.subtractExact(to.x(), from.x());
            dy = Math.subtractExact(to.y(), from.y());
        } catch (ArithmeticException ignored) {
            return 0;
        }
        boolean aligned = dx == 0 || dy == 0 || Math.abs(dx) == Math.abs(dy);
        int range = (int) Math.max(Math.abs(dx), Math.abs(dy));
        return aligned && range >= 1 && range <= 3 ? range : 0;
    }

    /** 守位点：从 Core 沿主轴朝威胁方向偏移 screen 格 */
    public static Cell guardPost(Cell corePos, Cell threat, UnitType type) {
        int screen = type == UnitType.VANGUARD ? VANGUARD_SCREEN : RANGER_SCREEN;
        long dx = Long.compare(threat.x(), corePos.x());
        long dy = Long.compare(threat.y(), corePos.y());
        if (Math.abs(dx) >= Math.abs(dy)) {
            return GridMath.offset(corePos, Long.signum(dx) * screen, 0).orElse(corePos);
        }
        return GridMath.offset(corePos, 0, Long.signum(dy) * screen).orElse(corePos);
    }

    /** 该格是否处于敌战斗单位的撤离半径内 */
    public static boolean underCombatPressure(Cell pos, List<EnemyView> enemies) {
        for (EnemyView enemy : enemies) {
            if (ThreatAssessor.attackRange(enemy) > 0
                    && ThreatAssessor.manhattan(pos, enemy.pos()) <= WORKER_FLEE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Core 预撤方向：在可行相邻格（非障碍/资源/占用）中选"到最近威胁距离"最大化的一步；
     * 同分时优先沿主威胁轴反向撤离（侧向平移不拉开轴向距离，易被夹击）。
     * 没有严格更优的方向时返回 null（原地防御好过撞进死路）。
     */
    public static String evadeDirection(Cell corePos, List<EnemyView> enemies,
                                        Set<Cell> obstacles, Set<Cell> resources, Set<Cell> occupied) {
        long currentScore = minThreatDistance(corePos, enemies);
        Cell nearestThreat = nearestThreat(corePos, enemies);
        String best = null;
        long bestScore = currentScore;
        int bestAxisBonus = -1;
        for (String direction : DIRECTIONS) {
            Cell next = corePos.move(direction);
            if (next.equals(corePos)) continue;
            if (obstacles.contains(next) || resources.contains(next) || occupied.contains(next)) {
                continue;
            }
            long score = minThreatDistance(next, enemies);
            int axisBonus = awayFromThreatAxis(corePos, next, nearestThreat) ? 1 : 0;
            if (score > bestScore || (score == bestScore && best != null && axisBonus > bestAxisBonus)) {
                bestScore = score;
                bestAxisBonus = axisBonus;
                best = direction;
            }
        }
        return best;
    }

    /** 该步是否沿主威胁轴远离（威胁在右则向左为真） */
    private static boolean awayFromThreatAxis(Cell from, Cell next, Cell threat) {
        if (threat == null) {
            return false;
        }
        long dx = Long.compare(threat.x(), from.x());
        long dy = Long.compare(threat.y(), from.y());
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx != 0 && Long.signum(next.x() - from.x()) == -Long.signum(dx);
        }
        return dy != 0 && Long.signum(next.y() - from.y()) == -Long.signum(dy);
    }

    private static Cell nearestThreat(Cell pos, List<EnemyView> enemies) {
        Cell nearest = null;
        long min = Long.MAX_VALUE;
        for (EnemyView enemy : enemies) {
            if (ThreatAssessor.attackRange(enemy) == 0) {
                continue;
            }
            long dist = ThreatAssessor.manhattan(pos, enemy.pos());
            if (dist < min) {
                min = dist;
                nearest = enemy.pos();
            }
        }
        return nearest;
    }

    private static long minThreatDistance(Cell pos, List<EnemyView> enemies) {
        long min = Long.MAX_VALUE;
        for (EnemyView enemy : enemies) {
            if (ThreatAssessor.attackRange(enemy) > 0) {
                min = Math.min(min, ThreatAssessor.manhattan(pos, enemy.pos()));
            }
        }
        return min;
    }
}
