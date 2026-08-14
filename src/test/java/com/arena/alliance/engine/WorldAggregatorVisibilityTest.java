package com.arena.alliance.engine;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAggregatorVisibilityTest {

    @Test
    void enemyDisappearsWhenNoCurrentMemberStateCanSeeIt() {
        WorldAggregator aggregator = new WorldAggregator();
        aggregator.updateMember(1L, 10L, "alice", 100,
                state(List.of(core("a-core", true, "alice", 0, 0),
                        unit("enemy-1", false, null, "VANGUARD", 2, 0))));
        assertTrue(aggregator.enemies().containsKey("enemy-1"));

        aggregator.updateMember(1L, 10L, "alice", 101,
                state(List.of(core("a-core", true, "alice", 0, 0))));
        assertFalse(aggregator.enemies().containsKey("enemy-1"));
    }

    @Test
    void enemyRemainsWhileAnotherMemberStillSeesIt() {
        WorldAggregator aggregator = new WorldAggregator();
        aggregator.updateMember(1L, 10L, "alice", 100,
                state(List.of(core("a-core", true, "alice", 0, 0),
                        unit("enemy-1", false, null, "RANGER", 2, 0))));
        aggregator.updateMember(2L, 20L, "bob", 100,
                state(List.of(core("b-core", true, "bob", 4, 0),
                        unit("enemy-1", false, null, "RANGER", 2, 0))));

        aggregator.updateMember(1L, 10L, "alice", 101,
                state(List.of(core("a-core", true, "alice", 0, 0))));
        assertTrue(aggregator.enemies().containsKey("enemy-1"));

        aggregator.updateMember(2L, 20L, "bob", 101,
                state(List.of(core("b-core", true, "bob", 4, 0))));
        assertFalse(aggregator.enemies().containsKey("enemy-1"));
    }

    @Test
    void allianceUnitSeenAsUncontrolledByAnotherMemberIsNotEnemy() {
        WorldAggregator aggregator = new WorldAggregator();
        aggregator.updateMember(1L, 10L, "alice", 100,
                state(List.of(core("a-core", true, "alice", 0, 0),
                        unit("a-worker", true, null, "WORKER", 1, 0))));
        aggregator.updateMember(2L, 20L, "bob", 100,
                state(List.of(core("b-core", true, "bob", 3, 0),
                        unit("a-worker", false, null, "WORKER", 1, 0))));

        assertFalse(aggregator.enemies().containsKey("a-worker"));
    }

    @Test
    void visibleMissingResourceIsRemovedButFoggedResourceIsRetained() {
        WorldAggregator aggregator = new WorldAggregator();
        Cell near = new Cell(2, 0);
        Cell far = new Cell(20, 0);

        aggregator.updateMember(1L, 10L, "alice", 100,
                state(List.of(core("a-core", true, "alice", 0, 0), resources(near))));
        assertTrue(aggregator.resources().containsKey(near));

        // near 仍在 Core 半径 5 的明确视野中，但新 state 没有 RESOURCE，因此必须删除。
        aggregator.updateMember(1L, 10L, "alice", 101,
                state(List.of(core("a-core", true, "alice", 0, 0))));
        assertFalse(aggregator.resources().containsKey(near));

        // 先在远处看到资源，随后 Core 离开，使该格进入战争迷雾；旧观察应保留。
        aggregator.updateMember(1L, 10L, "alice", 102,
                state(List.of(core("a-core", true, "alice", 20, 0), resources(far))));
        aggregator.updateMember(1L, 10L, "alice", 103,
                state(List.of(core("a-core", true, "alice", 0, 0))));
        assertTrue(aggregator.resources().containsKey(far));

        // 再次进入视野且 RESOURCE 缺失，删除最后已知资源。
        aggregator.updateMember(1L, 10L, "alice", 104,
                state(List.of(core("a-core", true, "alice", 20, 0))));
        assertFalse(aggregator.resources().containsKey(far));
    }

    @Test
    void obstacleBlocksNegativeResourceObservationBehindIt() {
        WorldAggregator aggregator = new WorldAggregator();
        Cell resource = new Cell(2, 0);
        Cell obstacle = new Cell(1, 0);

        // 首次从资源所在处看到并记录。
        aggregator.updateMember(1L, 10L, "alice", 100,
                state(List.of(core("a-core", true, "alice", 2, 0), resources(resource))));
        assertTrue(aggregator.resources().containsKey(resource));

        // Core 在原点，障碍 [1,0] 遮住 [2,0]。state 缺资源不能证明它消失。
        aggregator.updateMember(1L, 10L, "alice", 101,
                state(List.of(core("a-core", true, "alice", 0, 0), obstacles(obstacle))));
        assertTrue(aggregator.resources().containsKey(resource));
    }

    private static PlayerState state(List<GameObject> objects) {
        return new PlayerState("ACTIVE", null, 0, 0, null, objects, List.of());
    }

    private static GameObject core(String id, boolean controlled, String owner, long x, long y) {
        return new GameObject("CORE", id, controlled, owner, new Cell(x, y),
                5, 5, "NORMAL", null, null, null);
    }

    private static GameObject unit(String id, boolean controlled, String owner, String type, long x, long y) {
        return new GameObject("UNIT", id, controlled, owner, new Cell(x, y),
                2, null, null, type, null, null);
    }

    private static GameObject resources(Cell... cells) {
        return new GameObject("RESOURCE", null, null, null, null,
                null, null, null, null, null, List.of(cells));
    }

    private static GameObject obstacles(Cell... cells) {
        return new GameObject("OBSTACLE", null, null, null, null,
                null, null, null, null, null, List.of(cells));
    }
}
