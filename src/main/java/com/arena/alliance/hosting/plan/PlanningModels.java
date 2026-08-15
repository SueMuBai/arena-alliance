package com.arena.alliance.hosting.plan;

import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.engine.ChunkCoord;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.hosting.HostingConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.BitSet;
import java.util.Map;
import java.util.Set;

/**
 * 指挥管线的输入/输出模型（纯数据，可序列化为回放 fixture）。
 */
public final class PlanningModels {

    private PlanningModels() {
    }

    /** 一个托管账号的规划视图 */
    public record AccountView(long keyId, long userId, String gameUsername,
                              HostingConfig config, PlayerState state) {
    }

    /** worker↔资源租约：防止每 Tick 重分配抖动，跨账号不互抢 */
    public record Lease(long keyId, String unitId, long sinceTick) {
    }

    /** 当前联盟视野内的敌人（聚合器并集，已排除盟友对象） */
    public record EnemyView(String id, String kind, String unitType, Cell pos) {
    }

    /** 侦察任务：一个 worker 认领一个 chunk（32×32），带租约防抖动 */
    public record ScoutMission(long keyId, String unitId, long chunkX, long chunkY, long sinceTick) {

        /** chunk 中心格（侦察目标点） */
        public Cell center() {
            return new ChunkCoord(chunkX, chunkY).cell(16 * ChunkCoord.SIZE + 16).orElse(null);
        }
    }

    /**
     * 静止目标观测记录：连续在同一格观测到同一敌对象的次数。
     * 参考 agent 的"重复观测契约"——只有被确认长期静止的目标才值得远征清理。
     */
    public record TargetObservation(String enemyId, Cell pos, int sightings, long lastSeenTick) {
    }

    /** 清野打击任务：某账号的某个战斗单位被编入针对某目标的集火组 */
    public record StrikeMission(long keyId, String unitId, String enemyId, Cell target, long sinceTick) {
    }

    /**
     * 全联盟规划输入快照。
     *
     * @param allyIndex           全联盟对象索引（含非托管成员）——安全闸/驰援保护用
     * @param enemies             当前可见敌人并集
     * @param priorEnemyPositions 上一轮规划时的敌人位置（判定"移动/逼近"，静止远敌不动员）
     * @param exploredCoverage    联盟真实可见格覆盖位图
     * @param priorScouts         上一轮的侦察任务（租约延续，避免每 Tick 换扇区）
     * @param priorObservations   上一轮的静止目标观测记录（累计确认次数）
     * @param priorStrikes        上一轮的清野打击任务（保持编组，避免半路换目标）
     */
    public record PlanningInput(
            long tick,
            List<AccountView> accounts,
            Set<Cell> resources,
            Set<Cell> obstacles,
            Map<Cell, Lease> priorLeases,
            WorldAggregator.MemberIndex allyIndex,
            List<EnemyView> enemies,
            Map<String, Cell> priorEnemyPositions,
            Map<ChunkCoord, BitSet> exploredCoverage,
            Map<String, ScoutMission> priorScouts,
            Map<String, TargetObservation> priorObservations,
            Map<String, StrikeMission> priorStrikes
    ) {

        public static ChunkCoord chunkKey(long cx, long cy) {
            return new ChunkCoord(cx, cy);
        }

        public static long chunkOf(long coordinate) {
            return Math.floorDiv(coordinate, 32);
        }

        public boolean explored(ChunkCoord coord) {
            BitSet bits = exploredCoverage.get(coord);
            return bits != null && bits.cardinality() >= 820;
        }
    }

    /**
     * 规划输出：各账号计划（缺席 = 本 Tick 不提交）+ 各类跨 Tick 台账 + 决策日志。
     */
    public record PlanningResult(
            Map<Long, ObjectNode> plansByKey,
            Map<Cell, Lease> leases,
            Map<String, Cell> enemyPositions,
            Map<String, ScoutMission> scouts,
            Map<String, TargetObservation> observations,
            Map<String, StrikeMission> strikes,
            List<String> journal
    ) {
    }
}
