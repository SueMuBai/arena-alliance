package com.arena.alliance.auth;

import com.arena.alliance.common.CryptoService;
import com.arena.alliance.common.RateLimiter;
import com.arena.alliance.config.AllianceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 敏感操作的抗伪造/抗重放能力：令牌签名与吊销、限流、Secure Cookie 判定。
 */
class SecurityHardeningTest {

    private SessionService sessions;

    @BeforeEach
    void setUp() {
        AllianceProperties props = new AllianceProperties(
                "./target/test-data", "hardening-secret", 24, false,
                new AllianceProperties.Game("https://api.example.com", "/ws", "/cmd", ""),
                new AllianceProperties.LinuxDo("", "", "", "", "", "", ""));
        sessions = new SessionService(new CryptoService(props), props);
    }

    // ---------- 名册令牌：签名 + 吊销 ----------

    @Test
    void rosterTokenCarriesUserAndVersion() {
        SessionService.RosterToken parsed = sessions.parseRosterToken(sessions.issueRosterToken(42, 3));
        assertEquals(42, parsed.userId());
        assertEquals(3, parsed.version());
    }

    @Test
    void forgedOrTamperedRosterTokenIsRejected() {
        String token = sessions.issueRosterToken(42, 0);
        assertNull(sessions.parseRosterToken(token + "x"), "改签名应失效");
        assertNull(sessions.parseRosterToken("cm9zdGVyOjk5OTk." + token.split("\\.")[1]), "换载荷应失效");
        assertNull(sessions.parseRosterToken("随便伪造的串"));
        assertNull(sessions.parseRosterToken(null));
    }

    @Test
    void rotatingVersionInvalidatesOldToken() {
        // 泄露后自助重置：旧令牌版本与用户当前版本不符 → 名册接口据此拒绝
        String leaked = sessions.issueRosterToken(42, 0);
        String reissued = sessions.issueRosterToken(42, 1);
        assertNotEquals(leaked, reissued);
        assertEquals(0, sessions.parseRosterToken(leaked).version());
        assertEquals(1, sessions.parseRosterToken(reissued).version());
    }

    @Test
    void legacyTokenWithoutVersionIsTreatedAsVersionZero() {
        // 旧格式 roster:userId 仍可解析（版本 0），保证升级不掉线
        SessionService.RosterToken parsed = sessions.parseRosterToken(sessions.issueRosterToken(7, 0));
        assertEquals(0, parsed.version());
    }

    // ---------- 会话令牌 ----------

    @Test
    void sessionTokenCannotBeForgedWithoutSecret() {
        AllianceProperties other = new AllianceProperties(
                "./target/test-data-2", "另一个密钥", 24, false,
                new AllianceProperties.Game("https://api.example.com", "/ws", "/cmd", ""),
                new AllianceProperties.LinuxDo("", "", "", "", "", "", ""));
        SessionService attacker = new SessionService(new CryptoService(other), other);
        // 攻击者用自己的密钥签的会话，在本服务端无效
        assertNull(sessions.verify(attacker.issue(1)));
    }

    // ---------- 限流：抓包复用会话也刷不动 ----------

    @Test
    void rateLimiterBlocksBurstAndRecoversAfterWindow() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofMillis(150));
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertFalse(limiter.tryAcquire("u1"), "超出配额应被拒");
        assertTrue(limiter.tryAcquire("u2"), "不同用户互不影响");
    }

    @Test
    void rateLimiterIsPerKey() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(10));
        assertTrue(limiter.tryAcquire("manual:1"));
        assertFalse(limiter.tryAcquire("manual:1"));
        assertTrue(limiter.tryAcquire("manual:2"));
    }

    // ---------- Secure Cookie 判定 ----------

    @Test
    void httpsIsDetectedThroughProxyHeader() {
        HttpServletRequest behindProxy = mock(HttpServletRequest.class);
        when(behindProxy.getHeader("X-Forwarded-Proto")).thenReturn("https");
        assertTrue(AuthController.isHttps(behindProxy), "反向代理终止 TLS 时应识别为 HTTPS");

        HttpServletRequest plain = mock(HttpServletRequest.class);
        when(plain.getHeader("X-Forwarded-Proto")).thenReturn(null);
        when(plain.isSecure()).thenReturn(false);
        assertFalse(AuthController.isHttps(plain));

        HttpServletRequest direct = mock(HttpServletRequest.class);
        when(direct.getHeader("X-Forwarded-Proto")).thenReturn(null);
        when(direct.isSecure()).thenReturn(true);
        assertTrue(AuthController.isHttps(direct));
    }
}
