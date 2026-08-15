package com.arena.alliance.hosting.plan;

import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.engine.ChunkCoord;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.hosting.HostingConfig;
import com.arena.alliance.hosting.PilotMode;
import com.arena.alliance.hosting.plan.PlanningModels.AccountView;
import com.arena.alliance.hosting.plan.PlanningModels.EnemyView;
import com.arena.alliance.hosting.plan.PlanningModels.PlanningInput;
import com.arena.alliance.hosting.plan.PlanningModels.PlanningResult;
import com.arena.alliance.hosting.plan.ThreatAssessor.Assessment;
import com.arena.alliance.hosting.plan.ThreatAssessor.ThreatLevel;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.BitSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 防御条令：威胁评估矩阵、守位反击、Worker 撤离、Core 预撤、跨账号驰援。
 */
class DefenseDoctrineTest {

    private static final HostingConfig CFG = HostingConfig.DEFAULT;
    private static final Cell CORE = new Cell(0, 0);

    private static EnemyView ranger(String id, long x, long y) {
        return new EnemyView(id, "UNIT", "RANGER", new Cell(x, y));
    }

    private static EnemyView vanguard(String id, long x, long y) {
        return new EnemyView(id, "UNIT", "VANGUARD", new Cell(x, y));
    }

    private static GameObject core(String id, long x, long y) {
        return new GameObject("CORE", id, true, "owner", new Cell(x, y), 5, 5, "NORMAL", null, null, null);
    }

    private static GameObject unit(String id, String type, long x, long y) {
        return new GameObject("UNIT", id, true, null, new Cell(x, y),
                4, null, null, type, "WORKER".equals(type) ? 0 : null, null);
    }

    private static PlayerState state(List<GameObject> objects) {
        return new PlayerState("ACTIVE", null, 0, objects.size(), null, objects, List.of());
    }

    private static PlanningInput input(long tick, List<AccountView> accounts, Set<Cell> resources,
                                       List<EnemyView> enemies, Map<String, Cell> priorEnemies,
                                       WorldAggregator.MemberIndex allyIndex) {
        // 周边扇区已探明，隔离防御用例（避免空闲 worker 被派去侦察）
        Map<ChunkCoord, BitSet> explored = new java.util.HashMap<>();
        for (long cx = -4; cx <= 4; cx++) {
            for (long cy = -4; cy <= 4; cy++) {
                BitSet full = new BitSet(ChunkCoord.CELL_COUNT);
                full.set(0, ChunkCoord.CELL_COUNT);
                explored.put(PlanningInput.chunkKey(cx, cy), full);
            }
        }
        return new PlanningInput(tick, accounts, resources, Set.of(), Map.of(),
                allyIndex, enemies, priorEnemies, explored, Map.of(), Map.of(), Map.of());
    }

    // ---------- 威胁评估矩阵 ----------

    @Test
    void distantStationaryEnemyDoesNotMobilize() {
        // 远处静止的战斗单位只是观察对象，不进入 ALERT
        Cell far = new Cell(20, 0);
        Assessment a = ThreatAssessor.assess(CORE, PilotMode.BALANCED,
                List.of(ranger("e1", 20, 0)), Map.of("e1", far));
        assertEquals(ThreatLevel.NORMAL, a.level());
    }

    @Test
    void lateralMovementRaisesAlertOnly() {
        // 感知半径内横向移动（未逼近）→ ALERT，不预撤
        Assessment a = ThreatAssessor.assess(CORE, PilotMode.BALANCED,
                List.of(ranger("e1", 20, 3)), Map.of("e1", new Cell(20, 0)));
        assertEquals(ThreatLevel.ALERT, a.level());
    }

    @Test
    void approachingEnemyWithinTimeToRangeTriggersPreEvade() {
        // 逼近且时距 ≤16 → PRE_EVADE
        Assessment a = ThreatAssessor.assess(CORE, PilotMode.BALANCED,
                List.of(ranger("e1", 18, 0)), Map.of("e1", new Cell(22, 0)));
        assertEquals(ThreatLevel.PRE_EVADE, a.level());
    }

    @Test
    void enemyInsideFallbackRadiusTriggersPreEvade() {
        Assessment a = ThreatAssessor.assess(CORE, PilotMode.BALANCED,
                List.of(ranger("e1", 10, 0)), Map.of());
        assertEquals(ThreatLevel.PRE_EVADE, a.level());
    }

    @Test
    void enemyWithLegalShotOnCoreIsEngaged() {
        Assessment a = ThreatAssessor.assess(CORE, PilotMode.BALANCED,
                List.of(ranger("e1", 3, 0)), Map.of());
        assertEquals(ThreatLevel.ENGAGED, a.level());
    }

    @Test
    void turtleModeEvadesEarlier() {
        // 14 格：BALANCED 尚未预撤（>12），TURTLE 已预撤（≤16）
        List<EnemyView> enemies = List.of(ranger("e1", 14, 0));
        assertEquals(ThreatLevel.NORMAL,
                ThreatAssessor.assess(CORE, PilotMode.BALANCED, enemies, Map.of()).level());
        assertEquals(ThreatLevel.PRE_EVADE,
                ThreatAssessor.assess(CORE, PilotMode.TURTLE, enemies, Map.of()).level());
    }

    @Test
    void enemyCoreIsNotAThreat() {
        // 敌方 Core 没有攻击能力
        Assessment a = ThreatAssessor.assess(CORE, PilotMode.BALANCED,
                List.of(new EnemyView("ec", "CORE", null, new Cell(1, 0))), Map.of());
        assertEquals(ThreatLevel.NORMAL, a.level());
    }

    // ---------- 守位与反击 ----------

    @Test
    void vanguardSweepsAdjacentEnemy() {
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(List.of(core("a-core", 0, 0), unit("a-v", "VANGUARD", 2, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), Set.of(),
                List.of(vanguard("e1", 3, 0)), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        ObjectNode plan = r.plansByKey().get(1L);
        assertEquals("SWEEP", plan.at("/unit_actions/a-v/type").asText());
        assertEquals("RIGHT", plan.at("/unit_actions/a-v/direction").asText());
    }

    @Test
    void rangerShootsEnemyInLine() {
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(List.of(core("a-core", 0, 0), unit("a-r", "RANGER", 0, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), Set.of(),
                List.of(vanguard("e1", 2, 0)), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        ObjectNode plan = r.plansByKey().get(1L);
        assertEquals("SHOOT", plan.at("/unit_actions/a-r/type").asText());
        assertEquals(2, plan.at("/unit_actions/a-r/expected_cell/0").asInt());
        assertEquals(0, plan.at("/unit_actions/a-r/expected_cell/1").asInt());
    }

    @Test
    void rangerDoesNotShootOffLineTarget() {
        // (2,1) 不在直线/对角线上 → 不是合法射击
        assertEquals(0, DefensePlanner.shotRange(new Cell(0, 0), new Cell(2, 1)));
        assertEquals(3, DefensePlanner.shotRange(new Cell(0, 0), new Cell(3, 3)));
        assertEquals(0, DefensePlanner.shotRange(new Cell(0, 0), new Cell(4, 0)));
    }

    @Test
    void guardMovesToScreenPostFacingThreat() {
        // 威胁在右侧远处（ALERT）：Vanguard 移向右侧屏障位
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(List.of(core("a-core", 0, 0), unit("a-v", "VANGUARD", 0, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), Set.of(),
                List.of(ranger("e1", 20, 0)), Map.of("e1", new Cell(20, 5)),
                WorldAggregator.MemberIndex.EMPTY));

        ObjectNode plan = r.plansByKey().get(1L);
        assertEquals("MOVE", plan.at("/unit_actions/a-v/type").asText());
        assertEquals("RIGHT", plan.at("/unit_actions/a-v/direction").asText());
    }

    // ---------- Worker 撤离与 Core 预撤 ----------

    @Test
    void workerUnderPressureRetreatsInsteadOfHarvesting() {
        // Worker 在资源点旁但敌人逼近 → 回撤 Core，而不是继续采集
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(List.of(core("a-core", 0, 0), unit("a-w", "WORKER", 5, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), Set.of(new Cell(6, 0)),
                List.of(vanguard("e1", 7, 0)), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        ObjectNode plan = r.plansByKey().get(1L);
        assertEquals("MOVE", plan.at("/unit_actions/a-w/type").asText());
        assertEquals("LEFT", plan.at("/unit_actions/a-w/direction").asText(), "应朝 Core 撤退");
    }

    @Test
    void corePreEvadesAwayFromThreat() {
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(List.of(core("a-core", 0, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), Set.of(),
                List.of(ranger("e1", 8, 0)), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        ObjectNode plan = r.plansByKey().get(1L);
        assertEquals("START_MOVE", plan.at("/core_action/type").asText());
        assertEquals("LEFT", plan.at("/core_action/direction").asText(), "应远离右侧威胁");
    }

    @Test
    void evadePrefersAxisAwayFromThreatWhenScoresTie() {
        // 回归：曼哈顿距离相同时（左/上都是 9），必须沿主威胁轴反向撤，而非侧向平移
        String direction = DefensePlanner.evadeDirection(CORE,
                List.of(ranger("e1", 8, 0)), Set.of(), Set.of(), Set.of());
        assertEquals("LEFT", direction);
    }

    @Test
    void evadeReturnsNullWhenNoDirectionImproves() {
        // 四面被障碍封死：原地防御好过撞墙
        Set<Cell> walls = Set.of(new Cell(1, 0), new Cell(-1, 0), new Cell(0, 1), new Cell(0, -1));
        assertNull(DefensePlanner.evadeDirection(CORE,
                List.of(ranger("e1", 8, 0)), walls, Set.of(), Set.of()));
    }

    @Test
    void calmCoreKeepsProducing() {
        // 无威胁时 Core 继续生产，不误触发预撤
        AccountView a = new AccountView(1, 10, "alice", CFG,
                new PlayerState("ACTIVE", null, 10, 0, null, List.of(core("a-core", 0, 0)), List.of()));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), Set.of(),
                List.of(), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        assertEquals("SPAWN", r.plansByKey().get(1L).at("/core_action/type").asText());
    }

    // ---------- 跨账号驰援 ----------

    @Test
    void nearbyHostedAccountAssistsThreatenedAlly() {
        // 盟友（可以是非托管成员）在 [40,0] 被敌人围住；bob 距离适中 → 派 bob 驰援
        WorldAggregator.OwnerRef victim = new WorldAggregator.OwnerRef(
                "v-core", 99L, 9L, "victim", "CORE", null, 100, new Cell(40, 0));
        WorldAggregator.MemberIndex index = new WorldAggregator.MemberIndex(
                Map.of(victim.lastPos(), victim), Map.of(victim.objectId(), victim));

        AccountView bob = new AccountView(2, 20, "bob", CFG,
                state(List.of(core("b-core", 20, 0), unit("b-v", "VANGUARD", 20, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(bob), Set.of(),
                List.of(vanguard("e1", 41, 0)), Map.of(), index));

        ObjectNode plan = r.plansByKey().get(2L);
        assertNotNull(plan, "应产出驰援计划");
        assertEquals("MOVE", plan.at("/unit_actions/b-v/type").asText());
        assertEquals("RIGHT", plan.at("/unit_actions/b-v/direction").asText(), "应朝威胁点移动");
        assertTrue(r.journal().stream().anyMatch(l -> l.contains("驰援")));
    }

    @Test
    void turtleModeNeverLeavesHomeForAssist() {
        WorldAggregator.OwnerRef victim = new WorldAggregator.OwnerRef(
                "v-core", 99L, 9L, "victim", "CORE", null, 100, new Cell(40, 0));
        WorldAggregator.MemberIndex index = new WorldAggregator.MemberIndex(
                Map.of(victim.lastPos(), victim), Map.of(victim.objectId(), victim));

        AccountView turtle = new AccountView(2, 20, "bob", HostingConfig.of(PilotMode.TURTLE),
                state(List.of(core("b-core", 20, 0), unit("b-v", "VANGUARD", 20, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(turtle), Set.of(),
                List.of(vanguard("e1", 41, 0)), Map.of(), index));

        assertFalse(r.journal().stream().anyMatch(l -> l.contains("驰援")), "TURTLE 不出动");
        ObjectNode plan = r.plansByKey().get(2L);
        assertTrue(plan == null || !plan.at("/unit_actions").has("b-v"));
    }

    @Test
    void accountUnderOwnThreatDoesNotLeaveToAssist() {
        // bob 自己正被压迫（PRE_EVADE）→ 先自卫，不驰援远处盟友
        WorldAggregator.OwnerRef victim = new WorldAggregator.OwnerRef(
                "v-core", 99L, 9L, "victim", "CORE", null, 100, new Cell(40, 0));
        WorldAggregator.MemberIndex index = new WorldAggregator.MemberIndex(
                Map.of(victim.lastPos(), victim), Map.of(victim.objectId(), victim));

        AccountView bob = new AccountView(2, 20, "bob", CFG,
                state(List.of(core("b-core", 20, 0), unit("b-v", "VANGUARD", 20, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(bob), Set.of(),
                List.of(vanguard("e1", 41, 0), ranger("e2", 26, 0)), Map.of(), index));

        assertFalse(r.journal().stream().anyMatch(l -> l.contains("驰援")), "自身受威胁不出援");
    }

    @Test
    void assistNeverTargetsAllies() {
        // 红线复核：驰援过程中的动作同样过安全闸
        WorldAggregator.OwnerRef ally = new WorldAggregator.OwnerRef(
                "b-w1", 20L, 2L, "bob", "UNIT", "WORKER", 100, new Cell(1, 0));
        WorldAggregator.MemberIndex index = new WorldAggregator.MemberIndex(
                Map.of(ally.lastPos(), ally), Map.of(ally.objectId(), ally));

        // 盟友 worker 就站在 [1,0]，敌人在同格附近；Vanguard 不得扫向盟友格
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(List.of(core("a-core", 0, 0), unit("a-v", "VANGUARD", 0, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), Set.of(),
                List.of(new EnemyView("e1", "UNIT", "VANGUARD", new Cell(1, 0))), Map.of(), index));

        ObjectNode plan = r.plansByKey().get(1L);
        if (plan != null && plan.at("/unit_actions").has("a-v")) {
            assertEquals("WAIT", plan.at("/unit_actions/a-v/type").asText(),
                    "指向盟友所在格的攻击必须被安全闸剔除");
        }
    }
}
