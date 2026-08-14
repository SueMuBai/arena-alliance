package com.arena.alliance.engine;

import com.arena.alliance.game.GameJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlanSanitizerTest {

    @Test
    void replacesOnlyOffendingActionsAndPreservesEverythingElse() throws Exception {
        ObjectNode plan = (ObjectNode) GameJson.MAPPER.readTree("""
                {
                  "tick": 100,
                  "unit_actions": {
                    "a-v1": {"type": "SWEEP", "direction": "DOWN"},
                    "a-w2": {"type": "MOVE", "direction": "LEFT"},
                    "a-w3": {"type": "HARVEST"}
                  },
                  "core_action": {"type": "SPAWN", "unit_type": "WORKER"}
                }
                """);

        ObjectNode sanitized = PlanSanitizer.sanitize(plan, List.of("a-v1"));

        assertEquals("WAIT", sanitized.at("/unit_actions/a-v1/type").asText());
        assertFalse(sanitized.at("/unit_actions/a-v1").has("direction"), "WAIT 不允许携带多余字段");
        assertEquals("MOVE", sanitized.at("/unit_actions/a-w2/type").asText());
        assertEquals("LEFT", sanitized.at("/unit_actions/a-w2/direction").asText());
        assertEquals("HARVEST", sanitized.at("/unit_actions/a-w3/type").asText());
        assertEquals("SPAWN", sanitized.at("/core_action/type").asText());
        assertEquals(100, sanitized.get("tick").asInt());

        // 原计划不被修改
        assertEquals("SWEEP", plan.at("/unit_actions/a-v1/type").asText());
    }
}
