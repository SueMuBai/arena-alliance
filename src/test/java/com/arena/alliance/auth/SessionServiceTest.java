package com.arena.alliance.auth;

import com.arena.alliance.common.CryptoService;
import com.arena.alliance.config.AllianceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionServiceTest {

    private CryptoService crypto;
    private SessionService sessions;

    @BeforeEach
    void setUp() {
        AllianceProperties props = new AllianceProperties(
                "./target/test-data", "unit-test-secret", 24, false,
                new AllianceProperties.Game("https://api.example.com", "/ws", "/cmd", ""),
                new AllianceProperties.LinuxDo("", "", "", "", "", "", ""));
        crypto = new CryptoService(props);
        sessions = new SessionService(crypto, props);
    }

    @Test
    void issueAndVerifyRoundTrip() {
        String token = sessions.issue(42L);
        assertEquals(42L, sessions.verify(token));
    }

    @Test
    void tamperedTokenRejected() {
        String token = sessions.issue(42L);
        assertNull(sessions.verify(token + "x"));
        assertNull(sessions.verify("abc." + token.split("\\.")[1]));
        assertNull(sessions.verify(""));
        assertNull(sessions.verify(null));
    }

    @Test
    void expiredTokenRejected() {
        long past = java.time.Instant.now().minusSeconds(3600).getEpochSecond();
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("42:" + past).getBytes(StandardCharsets.UTF_8));
        String token = payload + "." + crypto.hmacBase64Url(payload);
        assertNull(sessions.verify(token));
    }

    @Test
    void cryptoEncryptDecryptRoundTrip() {
        String secret = "arena-hero-api-key-abcdef";
        String cipher = crypto.encrypt(secret);
        assertEquals(secret, crypto.decrypt(cipher));
    }

    @Test
    void rosterTokenRoundTripAndNotInterchangeable() {
        String rosterToken = sessions.issueRosterToken(7L);
        assertEquals(7L, sessions.verifyRosterToken(rosterToken));
        assertNull(sessions.verifyRosterToken(rosterToken + "x"));
        // roster 令牌不能当会话令牌用，反之亦然
        assertNull(sessions.verify(rosterToken));
        assertNull(sessions.verifyRosterToken(sessions.issue(7L)));
    }
}
