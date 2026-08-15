package com.arena.alliance.hosting.plan;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.CombatGeometry;
import com.arena.alliance.game.dto.GridMath;
import com.arena.alliance.game.dto.UnitType;
import com.arena.alliance.hosting.PilotMode;
import com.arena.alliance.hosting.plan.PlanningModels.EnemyView;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 威胁评估（参考 agent 威胁状态机的 M2 子集，阈值照抄官方策略文档）：
 *
 * | 等级 | 进入条件 |
 * |---|---|
 * | ENGAGED   | 敌战斗单位对 Core 有当前合法攻击（Vanguard 相邻 / Ranger 射程 3 内） |
 * | PRE_EVADE | 敌战斗单位进入回退圈（12 格；TURTLE 模式放宽为 16）或逼近且时距 ≤16 Tick |
 * | ALERT     | 感知半径 24 内的敌战斗单位发生了位置移动 |
 * | NORMAL    | 其余情况——远处静止的战斗单位只是观察，不动员 |
 *
 * 敌方 Core 无攻击能力，不构成威胁。
 */
public final class ThreatAssessor {

    public enum ThreatLevel {NORMAL, ALERT, PRE_EVADE, ENGAGED}

    public record Assessment(ThreatLevel level, Cell nearestThreat, long nearestDistance) {

        public static final Assessment CALM = new Assessment(ThreatLevel.NORMAL, null, Long.MAX_VALUE);
    }

    /** 12 格回退圈（参考 agent） */
    static final int FALLBACK_RADIUS = 12;
    /** TURTLE 模式提前预撤 */
    static final int TURTLE_FALLBACK_RADIUS = 16;
    /** 逼近敌的预撤时距阈值（Tick） */
    static final int TIME_TO_RANGE_LIMIT = 16;
    /** 移动警戒感知半径 */
    static final int AWARENESS_RADIUS = 24;

    private ThreatAssessor() {
    }

    public static Assessment assess(Cell corePos, PilotMode mode,
                                    List<EnemyView> enemies, Map<String, Cell> priorPositions) {
        return assess(corePos, mode, enemies, priorPositions, Set.of());
    }

    public static Assessment assess(Cell corePos, PilotMode mode,
                                    List<EnemyView> enemies, Map<String, Cell> priorPositions,
                                    Set<Cell> obstacles) {
        if (corePos == null) {
            return Assessment.CALM;
        }
        int fallbackRadius = mode == PilotMode.TURTLE ? TURTLE_FALLBACK_RADIUS : FALLBACK_RADIUS;
        ThreatLevel level = ThreatLevel.NORMAL;
        Cell nearest = null;
        long nearestDist = Long.MAX_VALUE;

        for (EnemyView enemy : enemies) {
            int range = attackRange(enemy);
            if (range == 0) {
                continue; // 非战斗单位（敌 Core / Worker / 未知类型）
            }
            long dist = GridMath.manhattan(corePos, enemy.pos());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = enemy.pos();
            }
            ThreatLevel enemyLevel;
            UnitType type = UnitType.fromName(enemy.unitType());
            boolean canAttackNow = type == UnitType.VANGUARD
                    ? CombatGeometry.canSweep(enemy.pos(), corePos)
                    : type == UnitType.RANGER
                    && CombatGeometry.canShoot(enemy.pos(), corePos, obstacles::contains);
            if (canAttackNow) {
                enemyLevel = ThreatLevel.ENGAGED;
            } else if (dist <= fallbackRadius) {
                enemyLevel = ThreatLevel.PRE_EVADE;
            } else {
                Cell prior = priorPositions.get(enemy.id());
                boolean moved = prior != null && !prior.equals(enemy.pos());
                boolean approaching = prior != null && dist < GridMath.manhattan(corePos, prior);
                if (approaching && dist <= (long) range + TIME_TO_RANGE_LIMIT) {
                    enemyLevel = ThreatLevel.PRE_EVADE;
                } else if (moved && dist <= AWARENESS_RADIUS) {
                    enemyLevel = ThreatLevel.ALERT;
                } else {
                    enemyLevel = ThreatLevel.NORMAL;
                }
            }
            if (enemyLevel.compareTo(level) > 0) {
                level = enemyLevel;
            }
        }
        return new Assessment(level, nearest, nearestDist);
    }

    /** 攻击射程：Ranger 3、Vanguard 1、其余 0（无威胁） */
    static int attackRange(EnemyView enemy) {
        if (!"UNIT".equals(enemy.kind())) {
            return 0;
        }
        UnitType type = UnitType.fromName(enemy.unitType());
        if (type == UnitType.RANGER) {
            return 3;
        }
        if (type == UnitType.VANGUARD) {
            return 1;
        }
        return 0;
    }

    static long manhattan(Cell a, Cell b) {
        return GridMath.manhattan(a, b);
    }
}
