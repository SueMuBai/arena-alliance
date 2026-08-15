package com.arena.alliance.auth;

import com.arena.alliance.common.CryptoService;
import com.arena.alliance.config.AllianceProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 会话令牌：base64url(uid:exp) + "." + HMAC 签名，存 HttpOnly Cookie。
 */
@Service
public class SessionService {

    public static final String COOKIE_NAME = "alliance_session";

    private final CryptoService crypto;
    private final Duration ttl;

    public SessionService(CryptoService crypto, AllianceProperties props) {
        this.crypto = crypto;
        this.ttl = Duration.ofHours(Math.max(1, props.sessionTtlHours()));
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(long userId) {
        long exp = Instant.now().plus(ttl).getEpochSecond();
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((userId + ":" + exp).getBytes(StandardCharsets.UTF_8));
        return payload + "." + crypto.hmacBase64Url(payload);
    }

    /** @return 有效则返回 userId，否则 null */
    public Long verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String payload = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        String expected = crypto.hmacBase64Url(payload);
        if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length != 2) {
                return null;
            }
            long exp = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() > exp) {
                return null;
            }
            return Long.parseLong(parts[0]);
        } catch (Exception e) {
            return null;
        }
    }

    /** 令牌载荷：userId + 版本号（版本号自增即可吊销此前签发的全部令牌） */
    public record RosterToken(long userId, int version) {
    }

    /**
     * 成员 agent 的机读接入令牌（无状态 HMAC 派生，带版本号）：
     * 用于 /api/alliance/roster 盟友名册接口。
     * 令牌泄露时，成员自助"重置令牌"会让旧令牌立即失效。
     */
    public String issueRosterToken(long userId, int version) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("roster:" + userId + ":" + version).getBytes(StandardCharsets.UTF_8));
        return payload + "." + crypto.hmacBase64Url(payload);
    }

    /** @return 签名有效则返回载荷（userId + 版本号），否则 null；版本号需由调用方与用户当前版本比对 */
    public RosterToken parseRosterToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String payload = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        String expected = crypto.hmacBase64Url(payload);
        if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            if (!decoded.startsWith("roster:")) {
                return null;
            }
            String[] parts = decoded.split(":");
            // roster:userId（旧格式，视为版本 0） / roster:userId:version
            long userId = Long.parseLong(parts[1]);
            int version = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            return new RosterToken(userId, version);
        } catch (Exception e) {
            return null;
        }
    }
}
