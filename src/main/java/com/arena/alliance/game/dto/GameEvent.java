package com.arena.alliance.game.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * state.events 中的一项（决议结果）。
 */
public record GameEvent(
        String eventId,
        long tick,
        String eventType,
        String reasonCode,
        String actorId,
        String targetId,
        Cell position,
        JsonNode values
) {
    public long valueInt(String key, long def) {
        if (values == null) return def;
        JsonNode n = values.get(key);
        return n == null ? def : n.asLong(def);
    }
}
