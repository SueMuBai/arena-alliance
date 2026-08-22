package com.arena.alliance.engine;

import com.arena.alliance.game.GameCommandClient;
import com.arena.alliance.game.dto.PlanReceipt;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.incident.IncidentType;
import com.arena.alliance.rules.RuleSettings;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MemberSessionOnlineTest {

    private MemberSession session;

    @AfterEach
    void tearDown() {
        if (session != null) session.stop();
    }

    @Test
    void tickMessageRefreshesOnlineStateWithoutWaitingForStateMessage() throws Exception {
        CountDownLatch markedOnline = new CountDownLatch(1);
        AtomicLong onlineTick = new AtomicLong(-1);
        MemberSession.Context context = new StubContext() {
            @Override
            public void onKeyOnline(long keyId, long tick) {
                onlineTick.set(tick);
                markedOnline.countDown();
            }
        };
        session = new MemberSession(7, 11, "member", "token", HttpClient.create(),
                "ws://localhost:1/ws", context);

        session.onMessage("{\"type\":\"tick\",\"data\":12345}");

        assertTrue(markedOnline.await(2, TimeUnit.SECONDS));
        assertEquals(12345, onlineTick.get());
        assertEquals(12345, session.currentTick());
    }

    private static class StubContext implements MemberSession.Context {

        @Override
        public WorldAggregator aggregator() {
            return mock(WorldAggregator.class);
        }

        @Override
        public GameCommandClient commands() {
            return mock(GameCommandClient.class);
        }

        @Override
        public CasualtyJudge judge() {
            return mock(CasualtyJudge.class);
        }

        @Override
        public RuleSettings rules() {
            return new RuleSettings();
        }

        @Override
        public void recordIncident(IncidentType type, long tick, Long attackerUserId, String attackerName,
                                   Long victimUserId, String victimName, String detail) {
        }

        @Override
        public boolean onGameUsernameLearned(long keyId, String gameUsername) {
            return true;
        }

        @Override
        public void onKeyOnline(long keyId, long tick) {
        }

        @Override
        public void onKeyInvalid(long keyId, String error) {
        }

        @Override
        public void requestStop(long keyId) {
        }

        @Override
        public void hostingConnected(long keyId) {
        }

        @Override
        public void hostingDisconnected(long keyId) {
        }

        @Override
        public void hostingReportState(long keyId, long userId, String gameUsername, long tick,
                                       PlayerState state) {
        }

        @Override
        public void hostingOnReceipt(long keyId, PlanReceipt receipt) {
        }

        @Override
        public void hostingRegisterPlatformPlan(long keyId, ObjectNode plan) {
        }

        @Override
        public void hostingUnregisterPlatformPlan(long keyId, ObjectNode plan) {
        }
    }
}
