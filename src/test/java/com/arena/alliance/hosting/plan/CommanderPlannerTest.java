package com.arena.alliance.hosting.plan;

import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.engine.ChunkCoord;
import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.game.dto.UnitType;
import com.arena.alliance.hosting.HostingConfig;
import com.arena.alliance.hosting.PilotMode;
import com.arena.alliance.hosting.plan.PlanningModels.AccountView;
import com.arena.alliance.hosting.plan.PlanningModels.Lease;
import com.arena.alliance.hosting.plan.PlanningModels.PlanningInput;
import com.arena.alliance.hosting.plan.PlanningModels.PlanningResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.BitSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommanderPlannerTest {

    private static final HostingConfig CFG = HostingConfig.DEFAULT;

    private static GameObject core(String id, long x, long y, int hp, int shield) {
        return new GameObject("CORE", id, true, "owner", new Cell(x, y), hp, shield, "NORMAL", null, null, null);
    }

    private static GameObject worker(String id, long x, long y, int cargo) {
        return new GameObject("UNIT", id, true, null, new Cell(x, y), 2, null, null, "WORKER", cargo, null);
    }

    private static PlayerState state(long resources, List<GameObject> objects) {
        return new PlayerState("ACTIVE", null, resources, countUnits(objects), null, objects, List.of());
    }

    private static int countUnits(List<GameObject> objects) {
        return (int) objects.stream().filter(GameObject::isUnit).count();
    }

    private static PlanningInput input(long tick, List<AccountView> accounts,
                                       Set<Cell> resources, Map<Cell, Lease> priorLeases) {
        // 周边扇区标记为已探明，隔离经济用例（侦察分工另有专门测试）
        Map<ChunkCoord, BitSet> explored = new java.util.HashMap<>();
        for (long cx = -4; cx <= 4; cx++) {
            for (long cy = -4; cy <= 4; cy++) {
                BitSet full = new BitSet(ChunkCoord.CELL_COUNT);
                full.set(0, ChunkCoord.CELL_COUNT);
                explored.put(PlanningInput.chunkKey(cx, cy), full);
            }
        }
        return new PlanningInput(tick, accounts, resources, Set.of(), priorLeases,
                WorldAggregator.MemberIndex.EMPTY, List.of(), Map.of(), explored, Map.of(), Map.of(), Map.of());
    }

    @Test
    void twoAccountsNeverShareOneResource() {
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(0, List.of(core("a-core", 0, 5, 5, 5), worker("a-w", 0, 0, 0))));
        AccountView b = new AccountView(2, 20, "bob", CFG,
                state(0, List.of(core("b-core", 10, 5, 5, 5), worker("b-w", 10, 0, 0))));
        // 资源 [1,0] 离 a 近，[11,0] 离 b 近；只有一个的话不能两人同抢
        PlanningResult result = CommanderPlanner.plan(input(100, List.of(a, b),
                Set.of(new Cell(1, 0), new Cell(11, 0)), Map.of()));

        assertEquals(2, result.leases().size());
        assertEquals("a-w", result.leases().get(new Cell(1, 0)).unitId());
        assertEquals("b-w", result.leases().get(new Cell(11, 0)).unitId());
        assertEquals("RIGHT", result.plansByKey().get(1L).at("/unit_actions/a-w/direction").asText());
        assertEquals("RIGHT", result.plansByKey().get(2L).at("/unit_actions/b-w/direction").asText());
    }

    @Test
    void singleResourceGoesToNearestWorkerOnly() {
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(0, List.of(core("a-core", 0, 5, 5, 5), worker("a-w", 0, 0, 0))));
        AccountView b = new AccountView(2, 20, "bob", CFG,
                state(0, List.of(core("b-core", 10, 5, 5, 5), worker("b-w", 9, 0, 0))));
        PlanningResult result = CommanderPlanner.plan(input(100, List.of(a, b),
                Set.of(new Cell(8, 0)), Map.of()));

        assertEquals(1, result.leases().size());
        assertEquals("b-w", result.leases().get(new Cell(8, 0)).unitId());
        // a 的 worker 没有任务，不产生动作
        ObjectNode planA = result.plansByKey().get(1L);
        assertTrue(planA == null || !planA.at("/unit_actions").has("a-w"));
    }

    @Test
    void workerOnResourceHarvestsAndLoadedWorkerDeposits() {
        AccountView a = new AccountView(1, 10, "alice", CFG, state(0, List.of(
                core("a-core", 0, 0, 5, 5),
                worker("a-h", 2, 0, 0),     // 站在资源上 → HARVEST
                worker("a-d", 0, 0, 1))));  // 满载站在 Core 上 → DEPOSIT
        PlanningResult result = CommanderPlanner.plan(input(100, List.of(a),
                Set.of(new Cell(2, 0)), Map.of()));

        ObjectNode plan = result.plansByKey().get(1L);
        assertEquals("HARVEST", plan.at("/unit_actions/a-h/type").asText());
        assertEquals("DEPOSIT", plan.at("/unit_actions/a-d/type").asText());
    }

    @Test
    void priorLeaseIsKeptEvenWhenAnotherWorkerIsCloser() {
        AccountView a = new AccountView(1, 10, "alice", CFG, state(0, List.of(
                core("a-core", 0, 5, 5, 5),
                worker("a-far", 5, 0, 0),
                worker("a-near", 2, 0, 0))));
        Cell resource = new Cell(1, 0);
        PlanningResult result = CommanderPlanner.plan(input(101, List.of(a),
                Set.of(resource), Map.of(resource, new Lease(1, "a-far", 100))));

        assertEquals("a-far", result.leases().get(resource).unitId());
    }

    @Test
    void productionFollowsStagesAndPriceBands() {
        assertEquals(UnitType.WORKER, ProductionPlanner.nextSpawnType(0, 0, 0, CFG));
        assertEquals(UnitType.VANGUARD, ProductionPlanner.nextSpawnType(12, 0, 0, CFG));
        assertEquals(UnitType.RANGER, ProductionPlanner.nextSpawnType(12, 3, 0, CFG));
        assertEquals(UnitType.WORKER, ProductionPlanner.nextSpawnType(12, 3, 4, CFG));
        assertNull(ProductionPlanner.nextSpawnType(23, 3, 4, CFG));

        // 官方价格档：人口 20-24 Worker 7，25-29 Worker 8
        assertEquals(5, ProductionPlanner.unitCost(UnitType.WORKER, 19));
        assertEquals(7, ProductionPlanner.unitCost(UnitType.WORKER, 20));
        assertEquals(8, ProductionPlanner.unitCost(UnitType.WORKER, 25));
        assertEquals(10, ProductionPlanner.unitCost(UnitType.VANGUARD, 0));
        assertEquals(12, ProductionPlanner.unitCost(UnitType.RANGER, 0));
    }

    @Test
    void coreSpawnsWorkerWhenAffordable() {
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(10, List.of(core("a-core", 0, 0, 5, 5))));
        PlanningResult result = CommanderPlanner.plan(input(100, List.of(a), Set.of(), Map.of()));

        ObjectNode plan = result.plansByKey().get(1L);
        assertEquals("SPAWN", plan.at("/core_action/type").asText());
        assertEquals("WORKER", plan.at("/core_action/unit_type").asText());
    }

    @Test
    void beaconAllowsRepairAboveFiveShield() {
        HostingConfig noProduction = new HostingConfig(PilotMode.BALANCED, 0, 0, 0);
        GameObject c = core("a-core", 0, 0, 5, 5);
        PlayerState withBeacon = new PlayerState("ACTIVE", null, 3, 0,
                new PlayerState.Beacon(new Cell(0, 0), "CARRIED", "a-core"), List.of(c), List.of());

        assertEquals("REPAIR_SHIELD",
                ProductionPlanner.coreAction(withBeacon, noProduction, 0).path("type").asText());
    }

    @Test
    void fullCoreCellPreventsSpawn() {
        GameObject c = core("a-core", 0, 0, 5, 5);
        PlayerState full = state(20, List.of(c, worker("a-w", 0, 0, 0)));

        assertNull(ProductionPlanner.coreAction(full, CFG, 0),
                "Core 与一个单位已经占满 2 个实体槽位");
    }

    @Test
    void damagedUnitAtCoreHealsBeforeOtherWork() {
        GameObject hurt = new GameObject("UNIT", "a-v", true, null, new Cell(0, 0),
                1, null, null, "VANGUARD", null, null); // 满血 4，缺 3
        AccountView a = new AccountView(1, 10, "alice", CFG,
                state(20, List.of(core("a-core", 0, 0, 5, 5), hurt)));
        PlanningResult result = CommanderPlanner.plan(input(100, List.of(a), Set.of(), Map.of()));

        assertEquals("HEAL", result.plansByKey().get(1L).at("/unit_actions/a-v/type").asText());
    }

    @Test
    void idleAllianceSubmitsNothing() {
        // 阵容满、无资源、无伤 → 不占提交配额
        AccountView a = new AccountView(1, 10, "alice",
                new HostingConfig(PilotMode.BALANCED, 0, 0, 0),
                state(0, List.of(core("a-core", 0, 0, 5, 5))));
        PlanningResult result = CommanderPlanner.plan(input(100, List.of(a), Set.of(), Map.of()));
        assertTrue(result.plansByKey().isEmpty());
    }

    @Test
    void allySafetyGateStripsActionsTargetingAllies() throws Exception {
        // 红线：即使上游产出攻击盟友的计划，安全闸必须剔除
        ObjectNode hostile = (ObjectNode) GameJson.MAPPER.readTree("""
                {"tick":100,"unit_actions":{"a-v1":{"type":"SWEEP","direction":"DOWN"}}}""");
        WorldAggregator.OwnerRef ally = new WorldAggregator.OwnerRef(
                "b-w1", 20L, 2L, "bob", "UNIT", "WORKER", 100, new Cell(10, 11));
        WorldAggregator.MemberIndex index = new WorldAggregator.MemberIndex(
                Map.of(ally.lastPos(), ally), Map.of(ally.objectId(), ally));

        ObjectNode safe = AllySafetyGate.enforce(10L, hostile,
                Map.of("a-v1", new Cell(10, 10)), index);

        assertEquals("WAIT", safe.at("/unit_actions/a-v1/type").asText());
        assertFalse(safe.at("/unit_actions/a-v1").has("direction"));
    }
}
