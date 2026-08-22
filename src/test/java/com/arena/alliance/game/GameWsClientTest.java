package com.arena.alliance.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GameWsClientTest {

    private DisposableServer server;
    private GameWsClient client;

    @AfterEach
    void tearDown() {
        if (client != null) client.stop();
        if (server != null) server.disposeNow();
    }

    @Test
    void reconnectsWhenOpenConnectionStopsReceivingMessages() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        server = HttpServer.create().port(0)
                .route(routes -> routes.ws("/ws", (in, out) -> {
                    connections.incrementAndGet();
                    return out.neverComplete();
                }))
                .bindNow();

        CountDownLatch disconnected = new CountDownLatch(1);
        client = new GameWsClient(HttpClient.create(),
                "ws://localhost:" + server.port() + "/ws", "token", "test",
                new GameWsClient.Listener() {
                    @Override
                    public void onOpen() {
                    }

                    @Override
                    public void onMessage(String text) {
                    }

                    @Override
                    public void onDisconnected(Integer closeCode, String detail, boolean fatal) {
                        disconnected.countDown();
                    }
                }, Duration.ofMillis(150));

        client.start();

        assertTrue(disconnected.await(3, TimeUnit.SECONDS), "静默连接应触发超时断开");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (connections.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(connections.get() >= 2, "静默连接超时后应自动重连");
    }
}
