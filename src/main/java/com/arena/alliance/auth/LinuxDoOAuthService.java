package com.arena.alliance.auth;

import com.arena.alliance.common.AllianceException;
import com.arena.alliance.config.AllianceProperties;
import com.arena.alliance.game.GameJson;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * LinuxDo OAuth2（参考 new-api 的 oauth/linuxdo.go 实现）：
 * authorize → 授权码换 token（Basic client 认证）→ /api/user 取用户信息。
 */
@Service
public class LinuxDoOAuthService {

    private static final Logger log = LoggerFactory.getLogger(LinuxDoOAuthService.class);

    public record LinuxDoUser(String id, String username, String name, int trustLevel,
                              boolean active, boolean silenced) {
    }

    private final AllianceProperties props;
    private final HttpClient http;

    public LinuxDoOAuthService(AllianceProperties props, HttpClient oauthHttpClient) {
        this.props = props;
        this.http = oauthHttpClient;
    }

    public boolean configured() {
        return props.linuxdo().configured();
    }

    public String buildAuthorizeUrl(String redirectUri, String state) {
        AllianceProperties.LinuxDo c = props.linuxdo();
        return c.authorizeUrl()
                + "?client_id=" + enc(c.clientId())
                + "&response_type=code"
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state);
    }

    public LinuxDoUser exchange(String code, String redirectUri) {
        AllianceProperties.LinuxDo c = props.linuxdo();
        String basic = Base64.getEncoder().encodeToString(
                (c.clientId() + ":" + c.clientSecret()).getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri);

        String tokenBody = http
                .headers(h -> {
                    h.set(HttpHeaderNames.AUTHORIZATION, "Basic " + basic);
                    h.set(HttpHeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded");
                    h.set(HttpHeaderNames.ACCEPT, "application/json");
                })
                .post()
                .uri(c.tokenUrl())
                .send((req, out) -> out.sendString(Mono.just(form), StandardCharsets.UTF_8))
                .responseSingle((res, bytes) -> bytes.asString(StandardCharsets.UTF_8).defaultIfEmpty(""))
                .block(Duration.ofSeconds(10));

        String accessToken = null;
        String message = null;
        try {
            JsonNode node = GameJson.MAPPER.readTree(tokenBody == null ? "{}" : tokenBody);
            accessToken = node.path("access_token").asText(null);
            message = node.path("message").asText(null);
        } catch (Exception ignored) {
        }
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("LinuxDo 换取 token 失败: {}", message != null ? message : tokenBody);
            throw AllianceException.badRequest("LinuxDo 授权失败" + (message == null ? "" : ("：" + message)));
        }

        final String bearer = accessToken;
        String userBody = http
                .headers(h -> {
                    h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearer);
                    h.set(HttpHeaderNames.ACCEPT, "application/json");
                })
                .get()
                .uri(c.userUrl())
                .responseSingle((res, bytes) -> bytes.asString(StandardCharsets.UTF_8).defaultIfEmpty(""))
                .block(Duration.ofSeconds(10));

        try {
            JsonNode u = GameJson.MAPPER.readTree(userBody == null ? "{}" : userBody);
            long id = u.path("id").asLong(0);
            if (id == 0) {
                throw AllianceException.badRequest("获取 LinuxDo 用户信息失败");
            }
            return new LinuxDoUser(String.valueOf(id),
                    u.path("username").asText(""),
                    u.path("name").asText(""),
                    u.path("trust_level").asInt(0),
                    u.path("active").asBoolean(true),
                    u.path("silenced").asBoolean(false));
        } catch (AllianceException e) {
            throw e;
        } catch (Exception e) {
            throw AllianceException.badRequest("解析 LinuxDo 用户信息失败");
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
