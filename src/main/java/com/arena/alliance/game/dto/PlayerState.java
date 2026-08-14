package com.arena.alliance.game.dto;

import java.util.List;

/**
 * 一个成员在某 Tick 的完整可见状态（state.data）。
 */
public record PlayerState(
        String status,
        Long respawnAtTick,
        long resources,
        int population,
        Beacon beacon,
        List<GameObject> objects,
        List<GameEvent> events
) {

    public record Beacon(Cell position, String status, String carrierId) {
    }

    public GameObject controlledCore() {
        if (objects == null) return null;
        for (GameObject o : objects) {
            if (o.isCore() && o.isControlled()) return o;
        }
        return null;
    }
}
