package com.arena.alliance.map;

import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.auth.SessionService;
import com.arena.alliance.common.AllianceException;
import com.arena.alliance.engine.AllianceEngine;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AllianceRosterControllerTest {

    @Test
    void rosterRejectsValidTokenWhenUserHasNoUploadedKey() {
        SessionService sessions = mock(SessionService.class);
        UserRepository users = mock(UserRepository.class);
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        WorldAggregator aggregator = mock(WorldAggregator.class);
        AllianceEngine engine = mock(AllianceEngine.class);
        AllianceRosterController controller =
                new AllianceRosterController(sessions, users, apiKeys, aggregator, engine);

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(User.Status.ACTIVE);
        when(sessions.parseRosterToken("valid-token"))
                .thenReturn(new com.arena.alliance.auth.SessionService.RosterToken(7L, 0));
        when(user.getRosterTokenVersion()).thenReturn(0);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        AllianceException denied = AllianceException.forbidden("请先上传一个游戏 Key");
        org.mockito.Mockito.doThrow(denied).when(apiKeys).requireUploadedKey(7L);

        AllianceException thrown = assertThrows(AllianceException.class,
                () -> controller.roster("valid-token", null));

        assertEquals(403, thrown.getStatus());
        verify(apiKeys).requireUploadedKey(7L);
        verifyNoInteractions(aggregator, engine);
    }

    @Test
    @SuppressWarnings("unchecked")
    void idsOnlyMemberDoesNotExposeCoordinatesOrUnitType() {
        SessionService sessions = mock(SessionService.class);
        UserRepository users = mock(UserRepository.class);
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        WorldAggregator aggregator = mock(WorldAggregator.class);
        AllianceEngine engine = mock(AllianceEngine.class);
        AllianceRosterController controller =
                new AllianceRosterController(sessions, users, apiKeys, aggregator, engine);

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(User.Status.ACTIVE);
        when(sessions.parseRosterToken("valid-token"))
                .thenReturn(new com.arena.alliance.auth.SessionService.RosterToken(7L, 0));
        when(user.getRosterTokenVersion()).thenReturn(0);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        GameObject core = new GameObject("CORE", "core-1", true, "alice", new Cell(12, 8),
                5, 5, "NORMAL", null, null, null);
        GameObject unit = new GameObject("UNIT", "unit-1", true, null, new Cell(11, 8),
                2, null, null, "WORKER", 0, null);
        PlayerState state = new PlayerState("ACTIVE", null, 5, 1, null,
                List.of(core, unit), List.of());
        WorldAggregator.MemberSnapshot member =
                new WorldAggregator.MemberSnapshot(11L, 7L, "alice", 100L, state, Instant.now());
        when(aggregator.members()).thenReturn(Map.of(11L, member));
        when(apiKeys.privacySettings(anyCollection())).thenReturn(Map.of(
                11L, new ApiKeyService.PrivacySettings(true, true)));

        Map<String, Object> data = controller.roster("valid-token", null).data();
        Map<String, Object> ally = ((List<Map<String, Object>>) data.get("allies")).getFirst();
        Map<String, Object> coreView = (Map<String, Object>) ally.get("core");
        Map<String, Object> unitView = ((List<Map<String, Object>>) ally.get("units")).getFirst();

        assertTrue((Boolean) ally.get("idsOnly"));
        assertEquals(List.of("core-1", "unit-1"), ally.get("objectIds"));
        assertEquals(Map.of("id", "core-1"), coreView);
        assertEquals(Map.of("id", "unit-1"), unitView);
        assertFalse(coreView.containsKey("pos"));
        assertFalse(unitView.containsKey("pos"));
        assertFalse(unitView.containsKey("type"));
    }
}
