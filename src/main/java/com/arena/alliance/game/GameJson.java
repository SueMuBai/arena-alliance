package com.arena.alliance.game;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameEvent;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlanReceipt;
import com.arena.alliance.game.dto.PlayerState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏协议 JSON 解析（手工映射，容忍未知字段，缺失字段为 null）。
 */
public final class GameJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private GameJson() {
    }

    /** 服务器 WS 消息：{type, data} */
    public record ServerMessage(String type, JsonNode data) {
    }

    public static ServerMessage parseMessage(String text) {
        try {
            JsonNode root = MAPPER.readTree(text);
            String type = root.path("type").asText(null);
            return new ServerMessage(type, root.get("data"));
        } catch (Exception e) {
            return new ServerMessage(null, null);
        }
    }

    public static PlayerState parseState(JsonNode data) {
        if (data == null) return null;
        List<GameObject> objects = new ArrayList<>();
        JsonNode objs = data.get("objects");
        if (objs != null && objs.isArray()) {
            for (JsonNode o : objs) {
                objects.add(parseObject(o));
            }
        }
        List<GameEvent> events = new ArrayList<>();
        JsonNode evs = data.get("events");
        if (evs != null && evs.isArray()) {
            for (JsonNode e : evs) {
                events.add(parseEvent(e));
            }
        }
        PlayerState.Beacon beacon = null;
        JsonNode b = data.get("champion_beacon");
        if (b != null) {
            beacon = new PlayerState.Beacon(
                    parseCell(b.get("position")),
                    text(b, "status"),
                    text(b, "carrier_id"));
        }
        return new PlayerState(
                text(data, "status"),
                data.hasNonNull("respawn_at_tick") ? data.get("respawn_at_tick").asLong() : null,
                data.path("resources").asLong(0),
                data.path("population").asInt(0),
                beacon,
                objects,
                events);
    }

    public static GameObject parseObject(JsonNode o) {
        List<Cell> positions = null;
        JsonNode pos = o.get("positions");
        if (pos != null && pos.isArray()) {
            positions = new ArrayList<>();
            for (JsonNode p : pos) {
                positions.add(parseCell(p));
            }
        }
        return new GameObject(
                text(o, "kind"),
                text(o, "id"),
                o.hasNonNull("controlled") ? o.get("controlled").asBoolean() : null,
                text(o, "owner_username"),
                parseCell(o.get("position")),
                intOrNull(o, "hp"),
                intOrNull(o, "shield"),
                text(o, "state"),
                text(o, "unit_type"),
                intOrNull(o, "cargo"),
                positions);
    }

    public static GameEvent parseEvent(JsonNode e) {
        return new GameEvent(
                text(e, "event_id"),
                e.path("tick").asLong(0),
                text(e, "event_type"),
                text(e, "reason_code"),
                text(e, "actor_id"),
                text(e, "target_id"),
                parseCell(e.get("position")),
                e.get("values"));
    }

    public static PlanReceipt parseReceipt(JsonNode data) {
        if (data == null) return null;
        JsonNode plan = data.get("plan");
        return new PlanReceipt(
                data.path("tick").asLong(0),
                text(data, "source"),
                text(data, "received_at"),
                plan instanceof ObjectNode on ? on : MAPPER.createObjectNode());
    }

    public static Cell parseCell(JsonNode n) {
        if (n == null || !n.isArray() || n.size() < 2) return null;
        return new Cell(n.get(0).asLong(), n.get(1).asLong());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }
}
