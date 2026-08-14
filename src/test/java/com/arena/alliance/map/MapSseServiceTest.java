package com.arena.alliance.map;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapSseServiceTest {

    @Test
    void personalizedSnapshotIsBuiltOncePerUser() {
        MapSseService service = new MapSseService();
        service.subscribe(7L);
        service.subscribe(7L);
        service.subscribe(8L);
        Map<Long, Integer> builds = new HashMap<>();

        service.broadcastSnapshots(userId -> {
            builds.merge(userId, 1, Integer::sum);
            return Map.of("viewer", userId);
        });

        assertEquals(3, service.clientCount());
        assertEquals(Map.of(7L, 1, 8L, 1), builds);
    }
}
