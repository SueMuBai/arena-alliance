package com.arena.alliance.engine;

import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasualtyJudgeTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    private static GameEvent event(String type, String reason, String actorId, String targetId,
                                   Cell pos, ObjectNode values) {
        return new GameEvent("e-" + System.nanoTime(), 101, type, reason, actorId, targetId, pos, values);
    }

    private static ObjectNode values(Object... kv) {
        ObjectNode n = GameJson.MAPPER.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Integer intValue) {
                n.put((String) kv[i], intValue);
            } else {
                n.put((String) kv[i], String.valueOf(v));
            }
        }
        return n;
    }

    private static final Function<String, WorldAggregator.OwnerRef> NO_OWNER = id -> null;

    @Test
    void destroyedByNamesAttributeDirectly() {
        ObjectNode v = GameJson.MAPPER.createObjectNode();
        v.putArray("destroyed_by").add("alice");
        var bobEvidence = new CasualtyJudge.UserEvidence(BOB, "bob", List.of(
                event("CORE_DESTROYED", "ATTACK", null, "b-core", new Cell(5, 5), v)));

        CasualtyJudge.JudgeResult result = CasualtyJudge.attribute(101,
                List.of(bobEvidence), NO_OWNER, Map.of("alice", ALICE, "bob", BOB));

        assertEquals(1, result.verdicts().size());
        CasualtyJudge.Verdict verdict = result.verdicts().get(0);
        assertEquals(ALICE, verdict.attackerUserId());
        assertEquals(BOB, verdict.victimUserId());
        assertEquals(CasualtyJudge.Severity.CORE_DESTROYED, verdict.severity());
        assertTrue(result.externalHits().isEmpty());
    }

    @Test
    void shotHitCrossAttributesUnitDeath() {
        var alice = new CasualtyJudge.UserEvidence(ALICE, "alice", List.of(
                event("SHOT_HIT", null, "a-r1", "b-w1", new Cell(7, 7), values("damage", 1))));
        var bob = new CasualtyJudge.UserEvidence(BOB, "bob", List.of(
                event("UNIT_DAMAGED", "ATTACK", null, "b-w1", new Cell(7, 7), values("damage", 1, "hp", 0))));

        CasualtyJudge.JudgeResult result = CasualtyJudge.attribute(101,
                List.of(alice, bob), NO_OWNER, Map.of("alice", ALICE, "bob", BOB));

        assertEquals(1, result.verdicts().size());
        assertEquals(CasualtyJudge.Severity.UNIT_DEATH, result.verdicts().get(0).severity());
        assertEquals(ALICE, result.verdicts().get(0).attackerUserId());
    }

    @Test
    void sweepPositionMatchAttributesUnitHit() {
        var alice = new CasualtyJudge.UserEvidence(ALICE, "alice", List.of(
                event("SWEEP_RESOLVED", null, "a-v1", null, new Cell(10, 11), values("targets_hit", 1))));
        var bob = new CasualtyJudge.UserEvidence(BOB, "bob", List.of(
                event("UNIT_DAMAGED", "ATTACK", null, "b-w1", new Cell(10, 11), values("damage", 1, "hp", 1))));

        CasualtyJudge.JudgeResult result = CasualtyJudge.attribute(101,
                List.of(alice, bob), NO_OWNER, Map.of("alice", ALICE, "bob", BOB));

        assertEquals(1, result.verdicts().size());
        assertEquals(CasualtyJudge.Severity.UNIT_HIT, result.verdicts().get(0).severity());
    }

    @Test
    void unattributedDamageBecomesExternalAlert() {
        var bob = new CasualtyJudge.UserEvidence(BOB, "bob", List.of(
                event("CORE_DAMAGED", "ATTACK", null, "b-core", new Cell(5, 5),
                        values("damage", 3, "shield_damage", 1, "hp_damage", 2))));

        CasualtyJudge.JudgeResult result = CasualtyJudge.attribute(101,
                List.of(bob), NO_OWNER, Map.of("bob", BOB));

        assertTrue(result.verdicts().isEmpty());
        assertEquals(1, result.externalHits().size());
        assertEquals(CasualtyJudge.Severity.CORE_HP, result.externalHits().get(0).severity());
        assertEquals(BOB, result.externalHits().get(0).victimUserId());
    }

    @Test
    void shieldOnlyDamageIsLowestSeverity() {
        var alice = new CasualtyJudge.UserEvidence(ALICE, "alice", List.of(
                event("SHOT_HIT", null, "a-r1", "b-core", new Cell(5, 5), values("damage", 1))));
        var bob = new CasualtyJudge.UserEvidence(BOB, "bob", List.of(
                event("CORE_DAMAGED", "ATTACK", null, "b-core", new Cell(5, 5),
                        values("damage", 1, "shield_damage", 1, "hp_damage", 0))));

        CasualtyJudge.JudgeResult result = CasualtyJudge.attribute(101,
                List.of(alice, bob), NO_OWNER, Map.of("alice", ALICE, "bob", BOB));

        assertEquals(1, result.verdicts().size());
        assertEquals(CasualtyJudge.Severity.SHIELD_ONLY, result.verdicts().get(0).severity());
    }

    @Test
    void selfInflictedEventsDoNotAccuseSelf() {
        // alice 自己打自己（同一用户多账号互殴不计）
        var alice = new CasualtyJudge.UserEvidence(ALICE, "alice", List.of(
                event("SHOT_HIT", null, "a-r1", "a-w9", new Cell(3, 3), values("damage", 1)),
                event("UNIT_DAMAGED", "ATTACK", null, "a-w9", new Cell(3, 3), values("damage", 1, "hp", 0))));

        CasualtyJudge.JudgeResult result = CasualtyJudge.attribute(101,
                List.of(alice), NO_OWNER, Map.of("alice", ALICE));

        assertTrue(result.verdicts().isEmpty());
        // 无法归因到其他成员 → 归为外部（保守告警）
        assertEquals(1, result.externalHits().size());
    }
}
