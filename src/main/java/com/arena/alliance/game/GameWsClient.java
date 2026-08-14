package com.arena.alliance.game;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个 apikey 的游戏 WebSocket 长连接（基于 Netty/reactor-netty）。
 * 按官方协议推荐做指数退避重连（250ms 起，5s 封顶，带抖动）；
 * 401 握手失败或 1008 关闭视为凭证失效，停止重试并通知上层。
 */
public class GameWsClient {

    private static final Logger log = LoggerFactory.getLogger(GameWsClient.class);
    private static final int MAX_FRAME = 8 * 1024 * 1024;

    private static final ScheduledExecutorService RECONNECT_TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "arena-ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    public interface Listener {

        void onOpen();

        void onMessage(String text);

        /** 连接结束（正常或异常）。fatal=true 表示凭证失效，不再重连。 */
        void onDisconnected(Integer closeCode, String detail, boolean fatal);
    }

    private final HttpClient client;
    private final String wsUrl;
    private final Listener listener;
    private final String tag;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Disposable current;
    private volatile long backoffMs = 250;

    public GameWsClient(HttpClient baseClient, String wsUrl, String token, String tag, Listener listener) {
        this.client = baseClient.headers(h -> h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token));
        this.wsUrl = wsUrl;
        this.listener = listener;
        this.tag = tag;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            connect();
        }
    }

    public void stop() {
        running.set(false);
        Disposable d = current;
        if (d != null) {
            d.dispose();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void connect() {
        if (!running.get()) return;
        AtomicReference<WebSocketCloseStatus> closeRef = new AtomicReference<>();
        current = client
                .websocket(WebsocketClientSpec.builder().maxFramePayloadLength(MAX_FRAME).build())
                .uri(wsUrl)
                .handle((in, out) -> {
                    backoffMs = 250;
                    listener.onOpen();
                    in.receiveCloseStatus().subscribe(closeRef::set, e -> { });
                    return in.aggregateFrames(MAX_FRAME)
                            .receive()
                            .asString(StandardCharsets.UTF_8)
                            .doOnNext(listener::onMessage)
                            .then();
                })
                .then()
                .subscribe(
                        v -> { },
                        err -> {
                            boolean fatal = isFatal(err);
                            if (fatal) {
                                running.set(false);
                            }
                            log.warn("[{}] WS 连接异常: {}", tag, String.valueOf(err.getMessage()));
                            listener.onDisconnected(null, String.valueOf(err.getMessage()), fatal);
                            scheduleReconnect();
                        },
                        () -> {
                            WebSocketCloseStatus cs = closeRef.get();
                            Integer code = cs == null ? null : cs.code();
                            boolean fatal = code != null && code == 1008;
                            if (fatal) {
                                running.set(false);
                            }
                            listener.onDisconnected(code, cs == null ? null : cs.reasonText(), fatal);
                            scheduleReconnect();
                        });
    }

    private boolean isFatal(Throwable err) {
        String m = String.valueOf(err.getMessage());
        return m.contains("401");
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        long base = backoffMs;
        long delay = base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
        backoffMs = Math.min(base * 2, 5000);
        RECONNECT_TIMER.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }
}
