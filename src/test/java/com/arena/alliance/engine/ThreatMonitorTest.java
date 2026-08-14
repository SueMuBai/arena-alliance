package com.arena.alliance.engine;

import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.Cell;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreatMonitorTest {

    private static final long ATTACKER = 1L;
    private static final long VICTIM = 2L;

    private static WorldAggregator.OwnerRef victimRef(String objectId, Cell pos) {
        return new WorldAggregator.OwnerRef(objectId, VICTIM, 20L, "bob", "UNIT", "WORKER", 100, pos);
    }

    private static WorldAggregator.MemberIndex index(WorldAggregator.OwnerRef ref) {
        return new WorldAggregator.MemberIndex(Map.of(ref.lastPos(), ref), Map.of(ref.objectId(), ref));
    }

    private static ObjectNode plan(String unitId, String actionJson) {
        try {
            return (ObjectNode) GameJson.MAPPER.readTree(
                    "{\"tick\":100,\"unit_actions\":{\"" + unitId + "\":" + actionJson + "}}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void sweepTowardsMemberIsOffense() {
        // 攻击者先锋在 [10,10]，向 DOWN 扫击 [10,11]，受害者 Worker 在 [10,11]
        ObjectNode plan = plan("a-v1", "{\"type\":\"SWEEP\",\"direction\":\"DOWN\"}");
        List<ThreatMonitor.Offense> offenses = ThreatMonitor.inspect(ATTACKER, plan,
                Map.of("a-v1", new Cell(10, 10)),
                index(victimRef("b-w1", new Cell(10, 11))));
        assertEquals(1, offenses.size());
        assertEquals("SWEEP", offenses.get(0).actionType());
        assertEquals(new Cell(10, 11), offenses.get(0).targetCell());
        assertEquals(VICTIM, offenses.get(0).victim().userId());
    }

    @Test
    void sweepTowardsEmptyCellIsNotOffense() {
        ObjectNode plan = plan("a-v1", "{\"type\":\"SWEEP\",\"direction\":\"UP\"}");
        List<ThreatMonitor.Offense> offenses = ThreatMonitor.inspect(ATTACKER, plan,
                Map.of("a-v1", new Cell(10, 10)),
                index(victimRef("b-w1", new Cell(10, 11))));
        assertTrue(offenses.isEmpty());
    }

    @Test
    void attackOnOwnObjectIsNotOffense() {
        // 目标格是攻击者自己的对象（同一 userId）
        WorldAggregator.OwnerRef own = new WorldAggregator.OwnerRef(
                "a-w9", ATTACKER, 10L, "alice", "UNIT", "WORKER", 100, new Cell(10, 11));
        ObjectNode plan = plan("a-v1", "{\"type\":\"SWEEP\",\"direction\":\"DOWN\"}");
        List<ThreatMonitor.Offense> offenses = ThreatMonitor.inspect(ATTACKER, plan,
                Map.of("a-v1", new Cell(10, 10)), index(own));
        assertTrue(offenses.isEmpty());
    }

    @Test
    void shootAtMemberCellIsOffense() {
        ObjectNode plan = plan("a-r1", "{\"type\":\"SHOOT\",\"expected_cell\":[12,13]}");
        List<ThreatMonitor.Offense> offenses = ThreatMonitor.inspect(ATTACKER, plan,
                Map.of("a-r1", new Cell(10, 10)),
                index(victimRef("b-c1", new Cell(12, 13))));
        assertEquals(1, offenses.size());
        assertEquals("SHOOT", offenses.get(0).actionType());
    }

    @Test
    void shootWithMemberTargetIdIsOffenseEvenIfCellStale() {
        // 精确目标模式：expected_cell 上索引里没人，但 target_id 是成员对象
        WorldAggregator.OwnerRef ref = victimRef("b-w1", new Cell(99, 99));
        ObjectNode plan = plan("a-r1",
                "{\"type\":\"SHOOT\",\"expected_cell\":[12,13],\"target_id\":\"b-w1\"}");
        List<ThreatMonitor.Offense> offenses = ThreatMonitor.inspect(ATTACKER, plan,
                Map.of("a-r1", new Cell(10, 10)), index(ref));
        assertEquals(1, offenses.size());
        assertEquals(VICTIM, offenses.get(0).victim().userId());
    }

    @Test
    void harvestAndMoveAreIgnored() {
        ObjectNode plan = plan("a-w1", "{\"type\":\"HARVEST\"}");
        ((ObjectNode) plan.get("unit_actions")).putObject("a-w2").put("type", "MOVE").put("direction", "DOWN");
        List<ThreatMonitor.Offense> offenses = ThreatMonitor.inspect(ATTACKER, plan,
                Map.of("a-w1", new Cell(10, 10), "a-w2", new Cell(10, 12)),
                index(victimRef("b-w1", new Cell(10, 11))));
        assertTrue(offenses.isEmpty());
    }
}
