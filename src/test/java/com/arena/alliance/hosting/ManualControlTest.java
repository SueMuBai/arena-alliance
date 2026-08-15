package com.arena.alliance.hosting;

import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.GridMath;
import com.arena.alliance.game.dto.PlayerState;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工接管：协议动作校验、可执行动作与目标格、Core 4-Tick 迁移语义、盟友红线标记。
 */
class ManualControlTest {

    private static ObjectNode action(String json) {
        try {
            return (ObjectNode) GameJson.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static GameObject core(long x, long y, String coreState) {
        return new GameObject("CORE", "a-core", true, "alice", new Cell(x, y),
                5, 5, coreState, null, null, null);
    }

    private static GameObject unit(String id, String type, long x, long y, Integer cargo, int hp) {
        return new GameObject("UNIT", id, true, null, new Cell(x, y), hp, null, null, type, cargo, null);
    }

    private static PlayerState state(long resources, List<GameObject> objects) {
        return new PlayerState("ACTIVE", null, resources, objects.size(), null, objects, List.of());
    }

    private static ManualTargeting.Action find(List<ManualTargeting.Action> actions, String type) {
        return actions.stream().filter(a -> a.type().equals(type)).findFirst().orElse(null);
    }

    // ---------- 协议校验 ----------

    @Test
    void acceptsWellFormedActions() {
        assertTrue(ManualControl.validate(false, "WORKER", action("{\"type\":\"HARVEST\"}")).ok());
        assertTrue(ManualControl.validate(false, "WORKER",
                action("{\"type\":\"MOVE\",\"direction\":\"UP\"}")).ok());
        assertTrue(ManualControl.validate(false, "VANGUARD",
                action("{\"type\":\"SWEEP\",\"direction\":\"DOWN\"}")).ok());
        assertTrue(ManualControl.validate(false, "RANGER",
                action("{\"type\":\"SHOOT\",\"expected_cell\":[3,3]}")).ok());
        assertTrue(ManualControl.validate(true, null,
                action("{\"type\":\"SPAWN\",\"unit_type\":\"RANGER\"}")).ok());
    }

    @Test
    void rejectsExtraFieldsThatWouldVoidWholePlan() {
        // 协议：动作只能包含它自己那一行列出的字段，否则整份计划被拒
        ManualControl.Validation v = ManualControl.validate(false, "WORKER",
                action("{\"type\":\"WAIT\",\"direction\":\"UP\"}"));
        assertFalse(v.ok());
        assertTrue(v.message().contains("不允许字段"));
    }

    @Test
    void rejectsWrongUnitTypeCapability() {
        assertFalse(ManualControl.validate(false, "WORKER",
                action("{\"type\":\"SWEEP\",\"direction\":\"UP\"}")).ok());
        assertFalse(ManualControl.validate(false, "VANGUARD",
                action("{\"type\":\"SHOOT\",\"expected_cell\":[1,1]}")).ok());
        assertFalse(ManualControl.validate(false, "RANGER",
                action("{\"type\":\"HARVEST\"}")).ok());
    }

    @Test
    void rejectsMalformedFields() {
        assertFalse(ManualControl.validate(false, "WORKER",
                action("{\"type\":\"MOVE\",\"direction\":\"NORTH\"}")).ok());
        assertFalse(ManualControl.validate(false, "RANGER",
                action("{\"type\":\"SHOOT\",\"expected_cell\":[1]}")).ok());
        assertFalse(ManualControl.validate(true, null,
                action("{\"type\":\"SPAWN\",\"unit_type\":\"HERO\"}")).ok());
        assertFalse(ManualControl.validate(false, "WORKER", action("{\"type\":\"FLY\"}")).ok());
    }

    @Test
    void rejectsUnsafeCoordinatesAndNonCanonicalTargetIds() {
        assertFalse(ManualControl.validate(false, "RANGER",
                action("{\"type\":\"SHOOT\",\"expected_cell\":[9007199254740992,0]}")).ok());
        assertFalse(ManualControl.validate(false, "RANGER",
                action("{\"type\":\"SHOOT\",\"expected_cell\":[1,1],\"target_id\":null}")).ok());
        assertFalse(ManualControl.validate(false, "RANGER",
                action("{\"type\":\"SHOOT\",\"expected_cell\":[1,1],"
                        + "\"target_id\":\"00000000-0000-0000-0000-000000000000\"}")).ok());
        assertFalse(ManualControl.validate(false, "RANGER",
                action("{\"type\":\"SHOOT\",\"expected_cell\":[1,1],"
                        + "\"target_id\":\"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA\"}")).ok());
    }

    @Test
    void coreAndUnitActionSetsAreDistinct() {
        // Core 不能 HARVEST，单位不能 SPAWN
        assertFalse(ManualControl.validate(true, null, action("{\"type\":\"HARVEST\"}")).ok());
        assertFalse(ManualControl.validate(false, "WORKER",
                action("{\"type\":\"SPAWN\",\"unit_type\":\"WORKER\"}")).ok());
    }

    // ---------- 可执行动作与目标格 ----------

    @Test
    void movingCoreOnlyOffersMigrationControls() {
        GameObject movingCore = core(0, 0, "MOVING");
        List<ManualTargeting.Action> actions = ManualTargeting.availableActions(
                movingCore, state(50, List.of(movingCore)), Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains);

        assertTrue(find(actions, "CANCEL_MOVE").enabled(), "迁移中可取消");
        assertFalse(find(actions, "SPAWN").enabled(), "迁移中不能生产");
        assertFalse(find(actions, "HEAL").enabled(), "迁移中不能治疗");
        assertFalse(find(actions, "START_MOVE").enabled(), "已在迁移中");
    }

    @Test
    void coreMigrationTargetsExcludeResourceAndObstacleCells() {
        GameObject c = core(0, 0, "NORMAL");
        List<ManualTargeting.Action> actions = ManualTargeting.availableActions(
                c, state(50, List.of(c)),
                Set.of(new Cell(1, 0))::contains,      // 右侧障碍
                Set.of(new Cell(-1, 0))::contains,     // 左侧资源地形（Core 不可入）
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains);

        ManualTargeting.Action move = find(actions, "START_MOVE");
        assertTrue(move.needsTarget());
        assertTrue(move.note().contains(String.valueOf(ManualTargeting.CORE_MOVE_REQUIRED_TICKS)),
                "应提示迁移需要 4 个 Tick");
        Map<Cell, ManualTargeting.Target> byCell = new java.util.HashMap<>();
        move.targets().forEach(t -> byCell.put(t.pos(), t));
        assertTrue(byCell.get(new Cell(1, 0)).blocked(), "障碍不可迁入");
        assertTrue(byCell.get(new Cell(-1, 0)).blocked(), "资源地形不可迁入");
        assertFalse(byCell.get(new Cell(0, 1)).blocked(), "空地可迁入");
    }

    @Test
    void allyCellsAreMarkedForbiddenForAttacks() {
        WorldAggregator.OwnerRef ally = new WorldAggregator.OwnerRef(
                "b-w1", 20L, 2L, "bob", "UNIT", "WORKER", 100, new Cell(1, 0));
        WorldAggregator.MemberIndex index = new WorldAggregator.MemberIndex(
                Map.of(ally.lastPos(), ally), Map.of(ally.objectId(), ally));

        GameObject vanguard = unit("a-v", "VANGUARD", 0, 0, null, 4);
        List<ManualTargeting.Action> actions = ManualTargeting.availableActions(
                vanguard, state(0, List.of(core(0, 5, "NORMAL"), vanguard)),
                Set.<Cell>of()::contains, Set.<Cell>of()::contains, index, Set.<Cell>of()::contains);

        ManualTargeting.Target allyCell = find(actions, "SWEEP").targets().stream()
                .filter(t -> t.pos().equals(new Cell(1, 0))).findFirst().orElseThrow();
        assertTrue(allyCell.ally(), "盟友格应标记为红线");
        assertTrue(allyCell.blocked(), "盟友格不可选");
        assertNotNull(allyCell.note());
    }

    @Test
    void ownCoreCellIsNotMistakenForForeignAlly() {
        GameObject ownCore = core(0, 0, "NORMAL");
        GameObject ownWorker = unit("a-w", "WORKER", 1, 0, 0, 2);
        WorldAggregator.OwnerRef coreOwner = new WorldAggregator.OwnerRef(
                "a-core", 10L, 1L, "alice", "CORE", null, 100, new Cell(0, 0));
        WorldAggregator.OwnerRef workerOwner = new WorldAggregator.OwnerRef(
                "a-w", 10L, 1L, "alice", "UNIT", "WORKER", 100, new Cell(1, 0));
        WorldAggregator.MemberIndex index = new WorldAggregator.MemberIndex(
                Map.of(coreOwner.lastPos(), coreOwner, workerOwner.lastPos(), workerOwner),
                Map.of(coreOwner.objectId(), coreOwner, workerOwner.objectId(), workerOwner));

        ManualTargeting.Target left = find(ManualTargeting.availableActions(
                ownWorker, state(0, List.of(ownCore, ownWorker)),
                Set.<Cell>of()::contains, Set.<Cell>of()::contains, index, Set.<Cell>of()::contains), "MOVE")
                .targets().stream().filter(t -> "LEFT".equals(t.direction())).findFirst().orElseThrow();

        assertFalse(left.ally());
        assertFalse(left.blocked(), "己方 Core 只占 1 个容量，Worker 可以移入");
    }

    @Test
    void shootTargetsCoverEightDirectionsAndStopAtObstacles() {
        GameObject ranger = unit("a-r", "RANGER", 0, 0, null, 2);
        List<ManualTargeting.Action> actions = ManualTargeting.availableActions(
                ranger, state(0, List.of(core(0, 5, "NORMAL"), ranger)),
                Set.of(new Cell(2, 0))::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains);

        Set<Cell> cells = new java.util.HashSet<>();
        find(actions, "SHOOT").targets().forEach(t -> cells.add(t.pos()));
        assertTrue(cells.contains(new Cell(3, 3)), "严格对角 3 格可射");
        assertTrue(cells.contains(new Cell(0, -3)), "正交 3 格可射");
        assertTrue(cells.contains(new Cell(2, 0)), "障碍格本身可作为目标");
        assertFalse(cells.contains(new Cell(3, 0)), "障碍后方被挡住");
        assertFalse(cells.contains(new Cell(2, 1)), "非对齐格不是合法目标");
    }

    @Test
    void workerContextualActionsReflectState() {
        // 满载且不在 Core 上：不能采集也不能上缴
        GameObject loaded = unit("a-w", "WORKER", 5, 5, 1, 2);
        List<ManualTargeting.Action> away = ManualTargeting.availableActions(
                loaded, state(0, List.of(core(0, 0, "NORMAL"), loaded)),
                Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains);
        assertFalse(find(away, "HARVEST").enabled());
        assertFalse(find(away, "DEPOSIT").enabled());

        // 满载站在静止 Core 上：可上缴
        GameObject atCore = unit("a-w", "WORKER", 0, 0, 1, 2);
        List<ManualTargeting.Action> home = ManualTargeting.availableActions(
                atCore, state(0, List.of(core(0, 0, "NORMAL"), atCore)),
                Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains);
        assertTrue(find(home, "DEPOSIT").enabled());

        // Core 迁移中：不能上缴
        GameObject movingCore = core(0, 0, "MOVING");
        List<ManualTargeting.Action> blocked = ManualTargeting.availableActions(
                atCore, state(0, List.of(movingCore, atCore)),
                Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains);
        assertFalse(find(blocked, "DEPOSIT").enabled(), "核心迁移中无法接收上缴");
    }

    @Test
    void emptyWorkerOnResourceCanHarvest() {
        GameObject worker = unit("a-w", "WORKER", 3, 0, 0, 2);
        List<ManualTargeting.Action> actions = ManualTargeting.availableActions(
                worker, state(0, List.of(core(0, 0, "NORMAL"), worker)),
                Set.<Cell>of()::contains, Set.of(new Cell(3, 0))::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains);
        assertTrue(find(actions, "HARVEST").enabled());
    }

    @Test
    void selfDestructIsMarkedDangerous() {
        GameObject c = core(0, 0, "NORMAL");
        List<ManualTargeting.Action> actions = ManualTargeting.availableActions(
                c, state(0, List.of(c)), Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains);
        assertTrue(find(actions, "SELF_DESTRUCT").dangerous(), "自毁需要前端二次确认");
    }

    @Test
    void beaconRaisesCoreShieldCapAndFullCoreCellBlocksSpawn() {
        GameObject c = core(0, 0, "NORMAL");
        PlayerState withBeacon = new PlayerState("ACTIVE", null, 5, 0,
                new PlayerState.Beacon(new Cell(0, 0), "CARRIED", "a-core"), List.of(c), List.of());
        assertEquals(10, ManualTargeting.coreShieldCap(withBeacon));
        assertTrue(find(ManualTargeting.availableActions(withBeacon.controlledCore(), withBeacon,
                Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains), "REPAIR_SHIELD").enabled());

        GameObject colocated = unit("a-w", "WORKER", 0, 0, 0, 2);
        PlayerState full = state(20, List.of(c, colocated));
        assertFalse(find(ManualTargeting.availableActions(c, full,
                Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains), "SPAWN").enabled());
    }

    @Test
    void targetGenerationNeverWrapsOrExposesUnsafeWebCoordinates() {
        GameObject edge = unit("a-w", "WORKER", Long.MAX_VALUE, 0, 0, 2);
        ManualTargeting.Action move = find(ManualTargeting.availableActions(
                edge, state(0, List.of(edge)), Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains), "MOVE");
        assertTrue(move.targets().isEmpty(), "int64 边界对象不能通过网页继续操作");
        assertTrue(move.targets().stream().noneMatch(t -> t.pos().x() == Long.MIN_VALUE));

        GameObject webEdge = unit("safe-edge", "WORKER", GridMath.JS_MAX_SAFE_INTEGER, 0, 0, 2);
        ManualTargeting.Action webMove = find(ManualTargeting.availableActions(
                webEdge, state(0, List.of(webEdge)), Set.<Cell>of()::contains, Set.<Cell>of()::contains,
                WorldAggregator.MemberIndex.EMPTY, Set.<Cell>of()::contains), "MOVE");
        assertTrue(webMove.targets().stream().noneMatch(t -> "RIGHT".equals(t.direction())),
                "网页操作不能把对象移出 JavaScript 安全整数范围");
    }
}
