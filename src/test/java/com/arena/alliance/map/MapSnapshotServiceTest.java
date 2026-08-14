package com.arena.alliance.map;

import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.engine.AllianceEngine;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.rules.RuleService;
import com.arena.alliance.rules.RuleSettings;
import com.arena.alliance.user.UserService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapSnapshotServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void hiddenCoreIsRemovedFromMemberAndEnemyLayers() {
        WorldAggregator aggregator = mock(WorldAggregator.class);
        AllianceEngine engine = mock(AllianceEngine.class);
        RuleService rules = mock(RuleService.class);
        UserService users = mock(UserService.class);
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        MapSseService sse = mock(MapSseService.class);
        MapSnapshotService service = new MapSnapshotService(aggregator, engine, rules, users, apiKeys, sse);

        GameObject controlledCore = core("core-alice", true, "alice", 12, 8);
        PlayerState state = new PlayerState("ACTIVE", null, 5, 0, null,
                List.of(controlledCore), List.of());
        WorldAggregator.MemberSnapshot member =
                new WorldAggregator.MemberSnapshot(11L, 7L, "alice", 100L, state, Instant.now());
        WorldAggregator.EnemySighting sighting = new WorldAggregator.EnemySighting(
                "core-alice", "CORE", null, "alice", new Cell(12, 8), 5, 5, 100L);

        when(aggregator.members()).thenReturn(Map.of(11L, member));
        when(aggregator.enemies()).thenReturn(Map.of("core-alice", sighting));
        when(aggregator.obstacles()).thenReturn(Map.of());
        when(aggregator.resources()).thenReturn(Map.of());
        when(aggregator.maxTick()).thenReturn(100L);
        when(rules.rules()).thenReturn(new RuleSettings());
        when(users.listAll()).thenReturn(List.of());
        when(apiKeys.privacySettings(anyCollection())).thenReturn(Map.of(
                11L, new ApiKeyService.PrivacySettings(false, false)));

        Map<String, Object> snapshot = service.build();
        Map<String, Object> memberView = ((List<Map<String, Object>>) snapshot.get("members")).getFirst();

        assertFalse(memberView.containsKey("core"));
        assertTrue(((List<?>) snapshot.get("enemies")).isEmpty());
    }

    private static GameObject core(String id, boolean controlled, String owner, long x, long y) {
        return new GameObject("CORE", id, controlled, owner, new Cell(x, y),
                5, 5, "NORMAL", null, null, null);
    }
}
