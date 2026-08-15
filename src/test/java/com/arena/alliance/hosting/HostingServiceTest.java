package com.arena.alliance.hosting;

import com.arena.alliance.apikey.ApiKey;
import com.arena.alliance.game.GameCommandClient;
import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.PlanReceipt;
import com.arena.alliance.incident.IncidentService;
import com.arena.alliance.rules.RuleService;
import com.arena.alliance.rules.RuleSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HostingServiceTest {

    private GameCommandClient commands;
    private HostingService service;

    @BeforeEach
    void setUp() {
        RuleService rules = mock(RuleService.class);
        when(rules.rules()).thenReturn(new RuleSettings());
        commands = mock(GameCommandClient.class);
        service = new HostingService(rules, mock(IncidentService.class), commands);
        registerRunning(1L);
    }

    @Test
    void staleTickIsNeverRetickedOrSubmitted() throws Exception {
        ObjectNode stale = plan(9);
        stale.with("unit_actions").set("unit-a", action("WAIT"));

        service.submit(1, 9, stale);
        TimeUnit.MILLISECONDS.sleep(30);

        verify(commands, never()).submitPlan(anyString(), anyString(), anyString());
    }

    @Test
    void rejectedManualPlanDoesNotCommitOverride() throws Exception {
        when(commands.submitPlan(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new GameCommandClient.CommandResult(422, "INVALID_COMMAND")));

        GameCommandClient.CommandResult result = service.submitManualAction(
                1, 10, "unit-a", false, action("WAIT")).get(2, TimeUnit.SECONDS);

        assertFalse(result.accepted());
        assertTrue(service.manuallyControlled(1, 10).isEmpty());
    }

    @Test
    void identicalPlatformFingerprintsAreConsumedOneReceiptAtATime() {
        ObjectNode platform = plan(10);
        platform.with("unit_actions").set("unit-a", action("WAIT"));
        service.registerPlatformPlan(1, platform);
        service.registerPlatformPlan(1, platform);
        PlanReceipt receipt = new PlanReceipt(10, "AGENT", "now", platform);

        service.onReceipt(1, receipt);
        service.onReceipt(1, receipt);
        assertEquals(HostingStatus.RUNNING, service.statusOf(1));

        service.onReceipt(1, receipt);
        assertEquals(HostingStatus.PAUSED_CONFLICT, service.statusOf(1));
    }

    @Test
    void concurrentManualSubmissionsSerializeAndPreserveBothOverrides() throws Exception {
        CompletableFuture<GameCommandClient.CommandResult> firstUpstream = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        when(commands.submitPlan(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            bodies.add(invocation.getArgument(1));
            if (calls.getAndIncrement() == 0) return firstUpstream;
            return CompletableFuture.completedFuture(new GameCommandClient.CommandResult(202, "accepted"));
        });

        CompletableFuture<GameCommandClient.CommandResult> first = service.submitManualAction(
                1, 10, "unit-a", false, action("WAIT"));
        CompletableFuture<GameCommandClient.CommandResult> second = service.submitManualAction(
                1, 10, "unit-b", false, action("SELF_DESTRUCT"));
        assertEquals(1, calls.get(), "第二次提交必须等待第一次结果");

        firstUpstream.complete(new GameCommandClient.CommandResult(202, "accepted"));
        assertTrue(first.get(2, TimeUnit.SECONDS).accepted());
        assertTrue(second.get(2, TimeUnit.SECONDS).accepted());
        assertEquals(2, calls.get());
        JsonNode merged = GameJson.MAPPER.readTree(bodies.get(1));
        assertEquals("WAIT", merged.at("/unit_actions/unit-a/type").asText());
        assertEquals("SELF_DESTRUCT", merged.at("/unit_actions/unit-b/type").asText());
        assertEquals(java.util.Set.of("unit-a", "unit-b"), service.manuallyControlled(1, 10));
    }

    private void registerRunning(long keyId) {
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(keyId);
        when(key.getUserId()).thenReturn(7L);
        when(key.getGameUsername()).thenReturn("alice");
        when(key.isHostingEnabled()).thenReturn(true);
        when(key.getHostingConfig()).thenReturn("{\"mode\":\"BALANCED\"}");
        service.register(key, "secret-token");
        service.onConnected(keyId);
        service.onState(keyId, 10);
    }

    private static ObjectNode plan(long tick) {
        ObjectNode plan = GameJson.MAPPER.createObjectNode();
        plan.put("tick", tick);
        plan.set("unit_actions", GameJson.MAPPER.createObjectNode());
        return plan;
    }

    private static ObjectNode action(String type) {
        ObjectNode action = GameJson.MAPPER.createObjectNode();
        action.put("type", type);
        return action;
    }
}
