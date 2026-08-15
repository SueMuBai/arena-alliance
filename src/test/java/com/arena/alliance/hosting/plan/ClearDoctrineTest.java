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
import com.arena.alliance.hosting.plan.PlanningModels.StrikeMission;
import com.arena.alliance.hosting.plan.PlanningModels.TargetObservation;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 清野战役：静止目标确认、跨账号集火、距离约束、留守底线、模式限制、盟友红线。
 */
class ClearDoctrineTest {

    private static GameObject core(String id, long x, long y) {
        return new GameObject("CORE", id, true, "owner", new Cell(x, y), 5, 5, "NORMAL", null, null, null);
    }

    private static GameObject unit(String id, String type, long x, long y) {
        return new GameObject("UNIT", id, true, null, new Cell(x, y), 4, null, null, type, null, null);
    }

    private static PlayerState state(List<GameObject> objects) {
        return new PlayerState("ACTIVE", null, 0, objects.size(), null, objects, List.of());
    }

    private static EnemyView enemy(String id, long x, long y) {
        return new EnemyView(id, "UNIT", "RANGER", new Cell(x, y));
    }

    /** 已确认静止的观测记录（达到确认次数） */
    private static Map<String, TargetObservation> confirmed(String id, Cell pos, long tick) {
        return Map.of(id, new TargetObservation(id, pos, ClearDoctrine.CONFIRM_SIGHTINGS, tick));
    }

    private static Map<ChunkCoord, BitSet> explored() {
        Map<ChunkCoord, BitSet> explored = new HashMap<>();
        for (long cx = -4; cx <= 4; cx++) {
            for (long cy = -4; cy <= 4; cy++) {
                BitSet full = new BitSet(ChunkCoord.CELL_COUNT);
                full.set(0, ChunkCoord.CELL_COUNT);
                explored.put(PlanningInput.chunkKey(cx, cy), full);
            }
        }
        return explored;
    }

    private static PlanningInput input(long tick, List<AccountView> accounts, List<EnemyView> enemies,
                                       Map<String, TargetObservation> observations,
                                       Map<String, StrikeMission> priorStrikes,
                                       WorldAggregator.MemberIndex allyIndex) {
        return new PlanningInput(tick, accounts, Set.of(), Set.of(), Map.of(),
                allyIndex, enemies, Map.of(), explored(), Map.of(), observations, priorStrikes);
    }

    /** CLEAR 模式账号，带 3 个战斗单位（够抽调） */
    private static AccountView clearAccount(long keyId, long userId, String name, long x) {
        return new AccountView(keyId, userId, name, HostingConfig.of(PilotMode.CLEAR),
                state(List.of(core(name + "-core", x, 0),
                        unit(name + "-v1", "VANGUARD", x, 0),
                        unit(name + "-v2", "VANGUARD", x, 1),
                        unit(name + "-r1", "RANGER", x, 2))));
    }

    // ---------- 目标确认 ----------

    @Test
    void stationaryTargetAccumulatesSightings() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        Cell pos = new Cell(20, 0);
        PlanningInput in = input(101, List.of(a), List.of(enemy("e1", 20, 0)),
                Map.of("e1", new TargetObservation("e1", pos, 1, 100)), Map.of(),
                WorldAggregator.MemberIndex.EMPTY);

        Map<String, TargetObservation> observations = ClearDoctrine.observe(in);
        assertEquals(2, observations.get("e1").sightings(), "同格再次观测应累加");
    }

    @Test
    void movingTargetResetsConfirmation() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        PlanningInput in = input(101, List.of(a), List.of(enemy("e1", 21, 0)),
                confirmed("e1", new Cell(20, 0), 100), Map.of(), WorldAggregator.MemberIndex.EMPTY);

        Map<String, TargetObservation> observations = ClearDoctrine.observe(in);
        assertEquals(1, observations.get("e1").sightings(), "目标移动 → 确认进度清零，不予远征");
    }

    @Test
    void unconfirmedTargetIsNotAttacked() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(enemy("e1", 20, 0)),
                Map.of(), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        assertTrue(r.strikes().isEmpty(), "未确认静止的目标不编组");
    }

    // ---------- 编组与集火 ----------

    @Test
    void confirmedTargetTriggersStrikeGroup() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(enemy("e1", 20, 0)),
                confirmed("e1", new Cell(20, 0), 99), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        assertFalse(r.strikes().isEmpty(), "确认目标应编组");
        assertTrue(r.strikes().size() <= 2, "必须留守 1 名（3 战斗单位最多派 2）");
        assertTrue(r.journal().stream().anyMatch(l -> l.contains("清野")));

        ObjectNode plan = r.plansByKey().get(1L);
        String striker = r.strikes().keySet().iterator().next();
        assertEquals("RIGHT", plan.at("/unit_actions/" + striker + "/direction").asText(), "应朝目标推进");
    }

    @Test
    void twoAccountsConcentrateFireOnSameTarget() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        AccountView b = clearAccount(2, 20, "bob", 40);
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a, b), List.of(enemy("e1", 20, 0)),
                confirmed("e1", new Cell(20, 0), 99), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        Set<Long> participatingAccounts = new HashSet<>();
        r.strikes().values().forEach(m -> participatingAccounts.add(m.keyId()));
        assertEquals(2, participatingAccounts.size(), "两账号应同时参与集火");
        assertTrue(r.strikes().size() <= ClearDoctrine.MAX_STRIKERS_PER_TARGET, "单目标集火人数有上限");
        r.strikes().values().forEach(m -> assertEquals("e1", m.enemyId()));
    }

    @Test
    void homeGuardIsAlwaysKept() {
        // 只有 1 个战斗单位 → 全部留守，不出击
        AccountView a = new AccountView(1, 10, "alice", HostingConfig.of(PilotMode.CLEAR),
                state(List.of(core("a-core", 0, 0), unit("a-v", "VANGUARD", 0, 0))));
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(enemy("e1", 20, 0)),
                confirmed("e1", new Cell(20, 0), 99), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        assertTrue(r.strikes().isEmpty(), "留守底线：最后一名战斗单位不外派");
    }

    // ---------- 距离与模式约束 ----------

    @Test
    void targetBeyondStrikeRangeIsIgnored() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(enemy("e1", 60, 0)),
                confirmed("e1", new Cell(60, 0), 99), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        assertTrue(r.strikes().isEmpty(), "超出 48 格出击半径不远征");
    }

    @Test
    void strikerPulledBeyondReleaseRangeIsRecalled() {
        // 单位被拉到 60 格（>56 释放半径）→ 解散任务
        AccountView a = new AccountView(1, 10, "alice", HostingConfig.of(PilotMode.CLEAR),
                state(List.of(core("a-core", 0, 0),
                        unit("a-v1", "VANGUARD", 60, 0),
                        unit("a-v2", "VANGUARD", 0, 1))));
        StrikeMission prior = new StrikeMission(1, "a-v1", "e1", new Cell(61, 0), 90);
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(enemy("e1", 61, 0)),
                confirmed("e1", new Cell(61, 0), 99), Map.of("a-v1", prior),
                WorldAggregator.MemberIndex.EMPTY));

        assertFalse(r.strikes().containsKey("a-v1"), "超出释放半径应撤回");
        assertTrue(r.journal().stream().anyMatch(l -> l.contains("释放半径")));
    }

    @Test
    void missionEndsWhenTargetDisappears() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        StrikeMission prior = new StrikeMission(1, "alice-v1", "e1", new Cell(20, 0), 90);
        // 目标已不在敌情列表（被清理或彻底失去视野）
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(),
                Map.of(), Map.of("alice-v1", prior), WorldAggregator.MemberIndex.EMPTY));

        assertTrue(r.strikes().isEmpty());
        assertTrue(r.journal().stream().anyMatch(l -> l.contains("解散编组")));
    }

    @Test
    void missionEndsWhenConfirmedTargetMoves() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        StrikeMission prior = new StrikeMission(1, "alice-v1", "e1", new Cell(20, 0), 90);
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(enemy("e1", 21, 0)),
                confirmed("e1", new Cell(20, 0), 99), Map.of("alice-v1", prior),
                WorldAggregator.MemberIndex.EMPTY));

        assertFalse(r.strikes().containsKey("alice-v1"), "目标移动后不得续用旧打击坐标");
    }

    @Test
    void onlyClearModeGoesOnCampaign() {
        for (PilotMode mode : List.of(PilotMode.BALANCED, PilotMode.TURTLE, PilotMode.SCOUT)) {
            AccountView a = new AccountView(1, 10, "alice", HostingConfig.of(mode),
                    state(List.of(core("a-core", 0, 0),
                            unit("a-v1", "VANGUARD", 0, 0), unit("a-v2", "VANGUARD", 0, 1))));
            PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(enemy("e1", 20, 0)),
                    confirmed("e1", new Cell(20, 0), 99), Map.of(), WorldAggregator.MemberIndex.EMPTY));
            assertTrue(r.strikes().isEmpty(), mode + " 模式不应参与清野");
        }
    }

    @Test
    void accountUnderPressureStaysHome() {
        // 自身被逼近（PRE_EVADE）→ 先自卫，不远征
        AccountView a = clearAccount(1, 10, "alice", 0);
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a),
                List.of(enemy("e1", 20, 0), enemy("e2", 8, 0)),
                confirmed("e1", new Cell(20, 0), 99), Map.of(), WorldAggregator.MemberIndex.EMPTY));

        assertTrue(r.strikes().isEmpty(), "自身受威胁时不外出清野");
    }

    // ---------- 红线 ----------

    @Test
    void campaignNeverTargetsAllies() {
        // 盟友对象与"敌人"同格（极端构造）：安全闸必须剔除攻击动作
        WorldAggregator.OwnerRef ally = new WorldAggregator.OwnerRef(
                "b-w1", 20L, 2L, "bob", "UNIT", "WORKER", 100, new Cell(1, 0));
        WorldAggregator.MemberIndex index = new WorldAggregator.MemberIndex(
                Map.of(ally.lastPos(), ally), Map.of(ally.objectId(), ally));

        AccountView a = clearAccount(1, 10, "alice", 0);
        PlanningResult r = CommanderPlanner.plan(input(100, List.of(a), List.of(enemy("e1", 1, 0)),
                confirmed("e1", new Cell(1, 0), 99), Map.of(), index));

        ObjectNode plan = r.plansByKey().get(1L);
        if (plan != null) {
            plan.at("/unit_actions").fields().forEachRemaining(e -> {
                String type = e.getValue().path("type").asText();
                assertNotEquals("SWEEP", type, "不得扫击盟友所在格");
                assertNotEquals("SHOOT", type, "不得射击盟友所在格");
            });
        }
    }

    @Test
    void observationsExpireAfterTtl() {
        AccountView a = clearAccount(1, 10, "alice", 0);
        Map<String, TargetObservation> stale = new HashMap<>();
        stale.put("old", new TargetObservation("old", new Cell(20, 0), 5, 10));
        PlanningInput in = input(100, List.of(a), List.of(), stale, Map.of(),
                WorldAggregator.MemberIndex.EMPTY);

        Map<String, TargetObservation> observations = ClearDoctrine.observe(in);
        assertFalse(observations.containsKey("old"), "超过 TTL 的观测应过期");
    }
}
