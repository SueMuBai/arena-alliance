package com.arena.alliance.game;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameEvent;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlanReceipt;
import com.arena.alliance.game.dto.PlayerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameJsonTest {

    @Test
    void parsesOfficialStateExample() {
        // 官方文档 state 消息示例
        String frame = """
                {
                  "type": "state",
                  "data": {
                    "status": "ACTIVE",
                    "resources": 5,
                    "population": 1,
                    "champion_beacon": {"position": [0, 0], "status": "CARRIED", "carrier_id": "c-1"},
                    "objects": [
                      {"kind": "CORE", "id": "core-1", "controlled": true, "owner_username": "arena_hero",
                       "position": [12, 8], "hp": 5, "shield": 5, "state": "NORMAL"},
                      {"kind": "UNIT", "id": "u-1", "controlled": true, "position": [11, 8],
                       "hp": 2, "unit_type": "WORKER", "cargo": 0},
                      {"kind": "OBSTACLE", "positions": [[4, 7], [4, 8]]}
                    ],
                    "events": [
                      {"event_id": "e-1", "tick": 10583, "event_type": "SHOT_HIT",
                       "actor_id": "a-1", "target_id": "t-1", "position": [120, 85], "values": {"damage": 1}}
                    ]
                  }
                }
                """;
        GameJson.ServerMessage message = GameJson.parseMessage(frame);
        assertEquals("state", message.type());
        PlayerState state = GameJson.parseState(message.data());
        assertNotNull(state);
        assertEquals("ACTIVE", state.status());
        assertEquals(5, state.resources());
        assertEquals(1, state.population());
        assertEquals(new Cell(0, 0), state.beacon().position());
        assertEquals("c-1", state.beacon().carrierId());

        GameObject core = state.controlledCore();
        assertNotNull(core);
        assertEquals("arena_hero", core.ownerUsername());
        assertEquals(new Cell(12, 8), core.position());

        GameObject obstacle = state.objects().get(2);
        assertEquals("OBSTACLE", obstacle.kind());
        assertEquals(2, obstacle.positions().size());

        GameEvent event = state.events().get(0);
        assertEquals("SHOT_HIT", event.eventType());
        assertEquals(10583, event.tick());
        assertEquals(1, event.valueInt("damage", 0));
        assertNull(event.reasonCode());
    }

    @Test
    void parsesReceivedReceipt() {
        String frame = """
                {
                  "type": "received",
                  "data": {
                    "tick": 10583,
                    "source": "AGENT",
                    "received_at": "2026-07-27T05:40:06.241Z",
                    "plan": {
                      "tick": 10583,
                      "unit_actions": {"u-1": {"type": "MOVE", "direction": "RIGHT"}},
                      "core_action": {"type": "WAIT"}
                    }
                  }
                }
                """;
        GameJson.ServerMessage message = GameJson.parseMessage(frame);
        PlanReceipt receipt = GameJson.parseReceipt(message.data());
        assertNotNull(receipt);
        assertEquals(10583, receipt.tick());
        assertTrue(receipt.isAgent());
        assertEquals("MOVE", receipt.plan().at("/unit_actions/u-1/type").asText());
    }

    @Test
    void toleratesGarbage() {
        assertNull(GameJson.parseMessage("not-json").type());
        assertNull(GameJson.parseState(null));
        assertNull(GameJson.parseReceipt(null));
    }
}
