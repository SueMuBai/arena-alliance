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
import com.arena.alliance.hosting.plan.PlanningModels.ScoutMission;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 侦察条令：覆盖度统计、扇区分工不重叠、编制配额、租约续期、遇敌撤离优先。
 */
class ScoutDoctrineTest {

    private static GameObject core(String id, long x, long y) {
        return new GameObject("CORE", id, true, "owner", new Cell(x, y), 5, 5, "NORMAL", null, null, null);
    }

    private static GameObject worker(String id, long x, long y) {
        return new GameObject("UNIT", id, true, null, new Cell(x, y), 2, null, null, "WORKER", 0, null);
    }

    private static PlayerState state(List<GameObject> objects) {
        return new PlayerState("ACTIVE", null, 0, objects.size(), null, objects, List.of());
    }

    /** 只把 Core 所在扇区标为已探明，周边留白供侦察 */
    private static Map<ChunkCoord, BitSet> onlyHomeExplored(long... chunkXY) {
        Map<ChunkCoord, BitSet> explored = new LinkedHashMap<>();
        for (int i = 0; i < chunkXY.length; i += 2) {
            BitSet full = new BitSet(ChunkCoord.CELL_COUNT);
            full.set(0, ChunkCoord.CELL_COUNT);
            explored.put(PlanningInput.chunkKey(chunkXY[i], chunkXY[i + 1]), full);
        }
        return explored;
    }

    private static PlanningInput input(long tick, List<AccountView> accounts, Map<ChunkCoord, BitSet> explored,
                                       Map<String, ScoutMission> priorScouts, List<EnemyView> enemies) {
        return new PlanningInput(tick, accounts, Set.of(), Set.of(), Map.of(),
                WorldAggregator.MemberIndex.EMPTY, enemies, Map.of(), explored, priorScouts, Map.of(), Map.of());
    }

    @Test
    void exploredChunksDerivedFromSharedMemory() {
        Map<ChunkCoord, BitSet> explored = ScoutDoctrine.exploredChunks(
                Set.of(new Cell(5, 5)),          // chunk (0,0)
                Set.of(new Cell(40, -3)));       // chunk (1,-1)
        assertTrue(explored.containsKey(PlanningInput.chunkKey(0, 0)));
        assertTrue(explored.containsKey(PlanningInput.chunkKey(1, -1)));
        assertFalse(explored.containsKey(PlanningInput.chunkKey(2, 2)));
    }

    @Test
    void negativeCoordinatesMapToCorrectChunk() {
        assertEquals(-1, PlanningInput.chunkOf(-1));
        assertEquals(-1, PlanningInput.chunkOf(-32));
        assertEquals(-2, PlanningInput.chunkOf(-33));
        assertEquals(0, PlanningInput.chunkOf(31));
        assertNotEquals(PlanningInput.chunkKey(1, -1), PlanningInput.chunkKey(-1, 1));
    }

    @Test
    void chunkNeedsAtLeast820VisibleCellsToBeComplete() {
        ChunkCoord coord = PlanningInput.chunkKey(-1, -1);
        BitSet coverage = new BitSet(ChunkCoord.CELL_COUNT);
        coverage.set(0, 819);
        PlanningInput in819 = input(100, List.of(), Map.of(coord, coverage), Map.of(), List.of());
        assertFalse(in819.explored(coord));

        coverage.set(819);
        PlanningInput in820 = input(100, List.of(), Map.of(coord, coverage), Map.of(), List.of());
        assertTrue(in820.explored(coord));
    }

    @Test
    void idleWorkerIsSentToUnexploredSector() {
        AccountView a = new AccountView(1, 10, "alice", HostingConfig.DEFAULT,
                state(List.of(core("a-core", 0, 0), worker("a-w", 0, 0))));
        PlanningResult r = CommanderPlanner.plan(
                input(100, List.of(a), onlyHomeExplored(0, 0), Map.of(), List.of()));

        assertEquals(1, r.scouts().size(), "空闲 worker 应领到侦察任务");
        ScoutMission mission = r.scouts().get("a-w");
        assertNotEquals(PlanningInput.chunkKey(0, 0),
                PlanningInput.chunkKey(mission.chunkX(), mission.chunkY()), "不该派往已探明的家门口");
        ObjectNode plan = r.plansByKey().get(1L);
        assertEquals("MOVE", plan.at("/unit_actions/a-w/type").asText());
    }

    @Test
    void twoAccountsNeverScoutTheSameSector() {
        AccountView a = new AccountView(1, 10, "alice", HostingConfig.DEFAULT,
                state(List.of(core("a-core", 0, 0), worker("a-w", 0, 0))));
        AccountView b = new AccountView(2, 20, "bob", HostingConfig.DEFAULT,
                state(List.of(core("b-core", 2, 0), worker("b-w", 2, 0))));
        PlanningResult r = CommanderPlanner.plan(
                input(100, List.of(a, b), onlyHomeExplored(0, 0), Map.of(), List.of()));

        assertEquals(2, r.scouts().size());
        ScoutMission ma = r.scouts().get("a-w");
        ScoutMission mb = r.scouts().get("b-w");
        assertNotEquals(PlanningInput.chunkKey(ma.chunkX(), ma.chunkY()),
                PlanningInput.chunkKey(mb.chunkX(), mb.chunkY()), "两账号扇区必须互斥");
    }

    @Test
    void scoutModeDoublesTheDetachment() {
        AccountView scout = new AccountView(1, 10, "alice", HostingConfig.of(PilotMode.SCOUT),
                state(List.of(core("a-core", 0, 0), worker("a-w1", 0, 0), worker("a-w2", 1, 0))));
        PlanningResult r = CommanderPlanner.plan(
                input(100, List.of(scout), onlyHomeExplored(0, 0), Map.of(), List.of()));
        assertEquals(2, r.scouts().size(), "SCOUT 模式编制加倍");

        AccountView balanced = new AccountView(1, 10, "alice", HostingConfig.DEFAULT,
                state(List.of(core("a-core", 0, 0), worker("a-w1", 0, 0), worker("a-w2", 1, 0))));
        PlanningResult r2 = CommanderPlanner.plan(
                input(100, List.of(balanced), onlyHomeExplored(0, 0), Map.of(), List.of()));
        assertEquals(1, r2.scouts().size(), "普通模式只派 1 名");
    }

    @Test
    void turtleModeKeepsWorkersHome() {
        AccountView turtle = new AccountView(1, 10, "alice", HostingConfig.of(PilotMode.TURTLE),
                state(List.of(core("a-core", 0, 0), worker("a-w", 0, 0))));
        PlanningResult r = CommanderPlanner.plan(
                input(100, List.of(turtle), onlyHomeExplored(0, 0), Map.of(), List.of()));
        assertTrue(r.scouts().isEmpty(), "TURTLE 不外派侦察");
    }

    @Test
    void turtleModeDropsPriorScoutMission() {
        AccountView turtle = new AccountView(1, 10, "alice", HostingConfig.of(PilotMode.TURTLE),
                state(List.of(core("a-core", 0, 0), worker("a-w", 0, 0))));
        ScoutMission prior = new ScoutMission(1, "a-w", 2, 2, 99);

        PlanningResult result = CommanderPlanner.plan(
                input(100, List.of(turtle), onlyHomeExplored(0, 0), Map.of("a-w", prior), List.of()));

        assertFalse(result.scouts().containsKey("a-w"));
    }

    @Test
    void missionIsRenewedInsteadOfReshuffledEachTick() {
        AccountView a = new AccountView(1, 10, "alice", HostingConfig.DEFAULT,
                state(List.of(core("a-core", 0, 0), worker("a-w", 0, 0))));
        ScoutMission prior = new ScoutMission(1, "a-w", 2, 2, 100);
        PlanningResult r = CommanderPlanner.plan(
                input(101, List.of(a), onlyHomeExplored(0, 0), Map.of("a-w", prior), List.of()));

        ScoutMission renewed = r.scouts().get("a-w");
        assertEquals(2, renewed.chunkX());
        assertEquals(2, renewed.chunkY());
        assertEquals(100, renewed.sinceTick(), "续约保留起始 Tick");
    }

    @Test
    void missionIsDroppedOnceSectorBecomesExplored() {
        AccountView a = new AccountView(1, 10, "alice", HostingConfig.DEFAULT,
                state(List.of(core("a-core", 0, 0), worker("a-w", 0, 0))));
        ScoutMission prior = new ScoutMission(1, "a-w", 2, 2, 100);
        // 扇区 (2,2) 现已探明 → 应改派别处
        PlanningResult r = CommanderPlanner.plan(
                input(101, List.of(a), onlyHomeExplored(0, 0, 2, 2), Map.of("a-w", prior), List.of()));

        ScoutMission mission = r.scouts().get("a-w");
        assertNotEquals(PlanningInput.chunkKey(2, 2),
                PlanningInput.chunkKey(mission.chunkX(), mission.chunkY()));
    }

    @Test
    void scoutUnderThreatRetreatsInsteadOfPushingOn() {
        // 侦察兵旁有敌战斗单位 → 撤离优先于继续推进
        AccountView a = new AccountView(1, 10, "alice", HostingConfig.DEFAULT,
                state(List.of(core("a-core", 0, 0), worker("a-w", 5, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), onlyHomeExplored(0, 0),
                Map.of("a-w", new ScoutMission(1, "a-w", 2, 0, 100)),
                List.of(new EnemyView("e1", "UNIT", "VANGUARD", new Cell(8, 0)))));

        ObjectNode plan = r.plansByKey().get(1L);
        assertEquals("LEFT", plan.at("/unit_actions/a-w/direction").asText(), "应朝 Core 撤退而非继续东进");
    }

    @Test
    void harvestTaskTakesPrecedenceOverScouting() {
        // 有采集任务的 worker 不会被抽去侦察
        AccountView a = new AccountView(1, 10, "alice", HostingConfig.DEFAULT,
                state(List.of(core("a-core", 0, 0), worker("a-w", 0, 0))));
        PlanningInput in = new PlanningInput(100, List.of(a), Set.of(new Cell(2, 0)), Set.of(), Map.of(),
                WorldAggregator.MemberIndex.EMPTY, List.of(), Map.of(), onlyHomeExplored(0, 0), Map.of(), Map.of(), Map.of());
        PlanningResult r = CommanderPlanner.plan(in);

        assertTrue(r.scouts().isEmpty(), "worker 已有采集任务，不应被派去侦察");
        assertEquals(1, r.leases().size());
    }
}
