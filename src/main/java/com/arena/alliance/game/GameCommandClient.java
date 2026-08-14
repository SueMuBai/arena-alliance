package com.arena.alliance.game;

import com.arena.alliance.config.AllianceProperties;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 作战计划提交客户端（共享 Netty 连接池，反制覆盖要求低延迟）。
 * 202 = 服务器已存储该计划（同来源槽位整体替换）。
 */
@Component
public class GameCommandClient {

    private final HttpClient base;
    private final String commandsUrl;

    public GameCommandClient(HttpClient baseHttpClient, AllianceProperties props) {
        this.base = baseHttpClient;
        this.commandsUrl = props.game().commandsUrl();
    }

    public record CommandResult(int status, String body) {

        public boolean accepted() {
            return status == 202;
        }
    }

    public CompletableFuture<CommandResult> submitPlan(String token, String planJson, String idempotencyKey) {
        return base
                .headers(h -> {
                    h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
                    h.set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                    h.set("Idempotency-Key", idempotencyKey);
                })
                .post()
                .uri(commandsUrl)
                .send((req, out) -> out.sendString(Mono.just(planJson), StandardCharsets.UTF_8))
                .responseSingle((res, bytes) -> bytes.asString(StandardCharsets.UTF_8)
                        .defaultIfEmpty("")
                        .map(b -> new CommandResult(res.status().code(), b)))
                .timeout(Duration.ofSeconds(8))
                .toFuture();
    }
}
