package com.arena.alliance.e2e;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Arena Hero 游戏服务器模拟器（按官方 asyncapi/openapi 消息格式）：
 * - WS /api/v1/game/ws：按 Bearer token 区分玩家，可向其推送 tick/state/received
 * - POST /api/v1/game/commands：记录收到的作战计划，回 202
 */
public class FakeArenaServer {

    public record Command(String token, String idempotencyKey, String body) {
    }

    private DisposableServer server;
    private final Map<String, List<Sinks.Many<String>>> clients = new ConcurrentHashMap<>();
    private final BlockingQueue<Command> commands = new LinkedBlockingQueue<>();
    private final AtomicInteger connections = new AtomicInteger();

    public void start() {
        server = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .ws("/api/v1/game/ws", (in, out) -> {
                            String token = bearer(in.headers().get("Authorization"));
                            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
                            clients.computeIfAbsent(token, k -> new CopyOnWriteArrayList<>()).add(sink);
                            connections.incrementAndGet();
                            return out.sendString(sink.asFlux(), StandardCharsets.UTF_8)
                                    .then()
                                    .doFinally(sig -> {
                                        List<Sinks.Many<String>> list = clients.get(token);
                                        if (list != null) {
                                            list.remove(sink);
                                        }
                                        connections.decrementAndGet();
                                    });
                        })
                        .post("/api/v1/game/commands", (req, res) -> {
                            String token = bearer(req.requestHeaders().get("Authorization"));
                            String idem = req.requestHeaders().get("Idempotency-Key");
                            return req.receive().aggregate().asString(StandardCharsets.UTF_8)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> {
                                        commands.add(new Command(token, idem, body));
                                        return res.status(202)
                                                .header("Content-Type", "application/json")
                                                .sendString(Mono.just(
                                                        "{\"accepted\":true,\"tick\":0,\"source\":\"AGENT\"," +
                                                                "\"received_at\":\"2026-01-01T00:00:00.000Z\"}"))
                                                .then();
                                    });
                        }))
                .bindNow();
    }

    public void stop() {
        if (server != null) {
            server.disposeNow();
        }
    }

    public int port() {
        return server.port();
    }

    public int connectionCount() {
        return connections.get();
    }

    public boolean awaitConnections(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (connections.get() >= expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    public Command awaitCommand(long timeoutMs) throws InterruptedException {
        return commands.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void sendRaw(String token, String frame) {
        for (Sinks.Many<String> sink : clients.getOrDefault(token, List.of())) {
            sink.tryEmitNext(frame);
        }
    }

    public void broadcastTick(long tick) {
        for (String token : clients.keySet()) {
            sendRaw(token, "{\"type\":\"tick\",\"data\":" + tick + "}");
        }
    }

    public void sendState(String token, String stateDataJson) {
        sendRaw(token, "{\"type\":\"state\",\"data\":" + stateDataJson + "}");
    }

    public void sendReceived(String token, long tick, String source, String planJson) {
        sendRaw(token, "{\"type\":\"received\",\"data\":{\"tick\":" + tick
                + ",\"source\":\"" + source + "\",\"received_at\":\"2026-01-01T00:00:00.000Z\",\"plan\":"
                + planJson + "}}");
    }

    private static String bearer(String header) {
        return header == null ? "" : header.replace("Bearer ", "");
    }
}
