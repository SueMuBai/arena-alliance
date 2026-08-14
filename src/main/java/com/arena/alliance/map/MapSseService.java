package com.arena.alliance.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;

/**
 * 地图页实时通道（SSE）：snapshot 全量快照 / incident 事件 / ping 心跳。
 */
@Service
public class MapSseService {

    private static final Logger log = LoggerFactory.getLogger(MapSseService.class);

    private record Subscriber(long userId, SseEmitter emitter) {
    }

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(userId, emitter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(e -> subscribers.remove(subscriber));
        return emitter;
    }

    public int clientCount() {
        return subscribers.size();
    }

    /** 每个用户只生成一次个性化快照，同一账号的多个标签页复用 payload。 */
    public void broadcastSnapshots(LongFunction<Object> payloadFactory) {
        Map<Long, Object> payloads = new HashMap<>();
        for (Subscriber subscriber : subscribers) {
            Object payload = payloads.computeIfAbsent(subscriber.userId(), payloadFactory::apply);
            send(subscriber, "snapshot", payload);
        }
    }

    public void broadcastIncident(Object payload) {
        broadcast("incident", payload);
    }

    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        broadcast("ping", "1");
    }

    private void broadcast(String event, Object payload) {
        for (Subscriber subscriber : subscribers) {
            send(subscriber, event, payload);
        }
    }

    private void send(Subscriber subscriber, String event, Object payload) {
        try {
            subscriber.emitter().send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 客户端已断开（刷新/关页）：直接移除即可。
            // 不要再调 complete()——响应已不可用，会触发 AsyncRequestNotUsableException。
            subscribers.remove(subscriber);
        }
    }
}
