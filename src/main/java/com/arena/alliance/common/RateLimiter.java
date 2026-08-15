package com.arena.alliance.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易滑动窗口限流（内存态，单实例）：防止会话被抓包复用后高频刷接口，
 * 也顺带挡住脚本对敏感写接口的滥用。
 */
public final class RateLimiter {

    private record Window(long windowStart, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int permits;
    private final long windowMillis;

    public RateLimiter(int permits, Duration window) {
        this.permits = permits;
        this.windowMillis = window.toMillis();
    }

    /** @return true 表示放行；false 表示超出配额 */
    public boolean tryAcquire(String key) {
        long now = Instant.now().toEpochMilli();
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || now - current.windowStart() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(current.windowStart(), current.count() + 1);
        });
        if (updated.count() <= permits) {
            return true;
        }
        // 超限时不再增长计数，避免长时间被自身刷爆
        windows.put(key, new Window(updated.windowStart(), permits + 1));
        return false;
    }

    /** 供测试与运维观察 */
    public void reset(String key) {
        windows.remove(key);
    }
}
