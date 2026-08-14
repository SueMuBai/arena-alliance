package com.arena.alliance.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 地图页实时通道（SSE）：snapshot 全量快照 / incident 事件 / ping 心跳。
 */
@Service
public class MapSseService {

    private static final Logger log = LoggerFactory.getLogger(MapSseService.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public int clientCount() {
        return emitters.size();
    }

    public void broadcastSnapshot(Object payload) {
        broadcast("snapshot", payload);
    }

    public void broadcastIncident(Object payload) {
        broadcast("incident", payload);
    }

    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        broadcast("ping", "1");
    }

    private void broadcast(String event, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 客户端已断开（刷新/关页）：直接移除即可。
                // 不要再调 complete()——响应已不可用，会触发 AsyncRequestNotUsableException。
                emitters.remove(emitter);
            }
        }
    }
}
