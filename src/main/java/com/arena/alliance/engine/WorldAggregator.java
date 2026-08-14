package com.arena.alliance.engine;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全联盟世界聚合器：
 * - 各成员自己的对象永远全量可见 → 合并即全联盟地图
 * - 障碍是永久地形 → 累积记忆（可持久化）
 * - 敌方目击、资源点带最后可见 tick，按时效淘汰
 * - objectOwners 留存 40 tick，用于战后事件归因（死亡单位已从 state 消失）
 */
@Component
public class WorldAggregator {

    public record OwnerRef(String objectId, long userId, long keyId, String gameUsername,
                           String kind, String unitType, long lastTick, Cell lastPos) {
    }

    public record EnemySighting(String id, String kind, String unitType, String ownerUsername,
                                Cell pos, Integer hp, Integer shield, long tick) {
    }

    public record MemberSnapshot(long keyId, long userId, String gameUsername, long tick,
                                 PlayerState state, Instant at) {
    }

    /** 成员对象索引：按格 / 按对象 ID（仅含近 2 tick 内确认的位置） */
    public record MemberIndex(Map<Cell, OwnerRef> byCell, Map<String, OwnerRef> byId) {

        public static final MemberIndex EMPTY = new MemberIndex(Map.of(), Map.of());
    }

    private final Map<Long, MemberSnapshot> members = new ConcurrentHashMap<>();
    private final Map<String, OwnerRef> objectOwners = new ConcurrentHashMap<>();
    private final Map<Cell, Boolean> obstacles = new ConcurrentHashMap<>();
    private final Queue<Cell> pendingObstacles = new ConcurrentLinkedQueue<>();
    private final Map<Cell, Long> resources = new ConcurrentHashMap<>();
    private final Map<String, EnemySighting> enemies = new ConcurrentHashMap<>();
    private volatile PlayerState.Beacon beacon;
    private final AtomicLong maxTick = new AtomicLong();
    private final AtomicLong version = new AtomicLong();

    private volatile MemberIndex cachedIndex = MemberIndex.EMPTY;
    private volatile long cachedIndexVersion = -1;

    public void updateMember(long keyId, long userId, String gameUsername, long tick, PlayerState state) {
        members.put(keyId, new MemberSnapshot(keyId, userId, gameUsername, tick, state, Instant.now()));
        long max = maxTick.updateAndGet(t -> Math.max(t, tick));

        if (state.objects() != null) {
            for (GameObject o : state.objects()) {
                String kind = o.kind() == null ? "" : o.kind();
                switch (kind) {
                    case "OBSTACLE" -> {
                        if (o.positions() != null) {
                            for (Cell c : o.positions()) {
                                if (obstacles.putIfAbsent(c, Boolean.TRUE) == null) {
                                    pendingObstacles.add(c);
                                }
                            }
                        }
                    }
                    case "RESOURCE" -> {
                        if (o.positions() != null) {
                            for (Cell c : o.positions()) {
                                resources.put(c, tick);
                            }
                        }
                    }
                    case "CORE", "UNIT" -> {
                        if (o.id() == null) break;
                        if (o.isControlled()) {
                            objectOwners.put(o.id(), new OwnerRef(o.id(), userId, keyId, gameUsername,
                                    o.kind(), o.unitType(), tick, o.position()));
                        } else {
                            enemies.put(o.id(), new EnemySighting(o.id(), o.kind(), o.unitType(),
                                    o.ownerUsername(), o.position(), o.hp(), o.shield(), tick));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        if (state.beacon() != null) {
            beacon = state.beacon();
        }
        if (tick >= max) {
            enemies.values().removeIf(e -> e.tick() < max - 8);
            objectOwners.values().removeIf(o -> o.lastTick() < max - 40);
            resources.values().removeIf(t -> t < max - 400);
        }
        version.incrementAndGet();
    }

    public void removeMember(long keyId) {
        members.remove(keyId);
        version.incrementAndGet();
    }

    /** 供威胁审查用的成员对象索引（带缓存，version 不变则复用） */
    public MemberIndex memberIndex() {
        long v = version.get();
        if (cachedIndexVersion == v) {
            return cachedIndex;
        }
        long max = maxTick.get();
        Map<Cell, OwnerRef> byCell = new HashMap<>();
        Map<String, OwnerRef> byId = new HashMap<>();
        for (OwnerRef o : objectOwners.values()) {
            if (o.lastTick() >= max - 2 && o.lastPos() != null) {
                byCell.put(o.lastPos(), o);
                byId.put(o.objectId(), o);
            }
        }
        MemberIndex idx = new MemberIndex(Map.copyOf(byCell), Map.copyOf(byId));
        cachedIndex = idx;
        cachedIndexVersion = v;
        return idx;
    }

    /** 事后归因：objectId → 归属成员（含近 40 tick 内已死亡对象） */
    public OwnerRef ownerOf(String objectId) {
        return objectId == null ? null : objectOwners.get(objectId);
    }

    public List<Cell> drainPendingObstacles(int max) {
        List<Cell> out = new ArrayList<>();
        Cell c;
        while (out.size() < max && (c = pendingObstacles.poll()) != null) {
            out.add(c);
        }
        return out;
    }

    /** 启动时从持久层恢复障碍记忆 */
    public void preloadObstacles(Iterable<Cell> cells) {
        for (Cell c : cells) {
            obstacles.put(c, Boolean.TRUE);
        }
        version.incrementAndGet();
    }

    public Map<Long, MemberSnapshot> members() {
        return members;
    }

    public Map<String, EnemySighting> enemies() {
        return enemies;
    }

    public Map<Cell, Boolean> obstacles() {
        return obstacles;
    }

    public Map<Cell, Long> resources() {
        return resources;
    }

    public PlayerState.Beacon beacon() {
        return beacon;
    }

    public long maxTick() {
        return maxTick.get();
    }

    public long version() {
        return version.get();
    }
}
