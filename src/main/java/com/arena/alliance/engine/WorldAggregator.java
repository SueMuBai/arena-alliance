package com.arena.alliance.engine;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全联盟世界聚合器：
 * - 各成员自己的对象永远全量可见 → 合并即全联盟地图
 * - 障碍是永久地形 → 累积记忆（可持久化）
 * - 敌方对象只保留当前至少一个成员可见的并集，不保留最后目击幻影
 * - 资源保留最后已知状态；格子重新进入视野且 state 不再包含时删除
 * - objectOwners 留存 40 tick，用于战后事件归因（死亡单位已从 state 消失）
 */
@Component
public class WorldAggregator {

    public static final int TERRAIN_CHUNK_SIZE = 32;

    public record OwnerRef(String objectId, long userId, long keyId, String gameUsername,
                           String kind, String unitType, long lastTick, Cell lastPos) {
    }

    public record EnemySighting(String id, String kind, String unitType, String ownerUsername,
                                Cell pos, Integer hp, Integer shield, long tick) {
    }

    public record MemberSnapshot(long keyId, long userId, String gameUsername, long tick,
                                 PlayerState state, Instant at) {
    }

    public record TerrainChunk(long chunkX, long chunkY, BitSet cells) {
    }

    public record TerrainWindow(String epoch, long revision, List<TerrainChunk> chunks) {
    }

    public record TerrainChange(long revision, Cell cell) {
    }

    private record TerrainChunkKey(long x, long y) {
    }

    /** 成员对象索引：按格 / 按对象 ID（仅含近 2 tick 内确认的位置） */
    public record MemberIndex(Map<Cell, OwnerRef> byCell, Map<String, OwnerRef> byId) {

        public static final MemberIndex EMPTY = new MemberIndex(Map.of(), Map.of());
    }

    private final Map<Long, MemberSnapshot> members = new ConcurrentHashMap<>();
    private final Map<String, OwnerRef> objectOwners = new ConcurrentHashMap<>();
    private final Map<Cell, Boolean> obstacles = new ConcurrentHashMap<>();
    private final Map<TerrainChunkKey, BitSet> obstacleChunks = new HashMap<>();
    private final Queue<Cell> pendingObstacles = new ConcurrentLinkedQueue<>();
    private final Queue<TerrainChange> pendingTerrainChanges = new ConcurrentLinkedQueue<>();
    private final String terrainEpoch = UUID.randomUUID().toString();
    private final AtomicLong terrainRevision = new AtomicLong();
    private final Map<Cell, Long> resources = new ConcurrentHashMap<>();
    /**
     * 当前联盟视野中的敌人并集。成员 state 是完整快照；每次状态更新后从 members 现有快照重建，
     * 因此敌人一旦不再被任何成员看见就立即消失，不保留最后目击幻影。
     */
    private final Map<String, EnemySighting> enemies = new ConcurrentHashMap<>();
    private volatile PlayerState.Beacon beacon;
    private final AtomicLong maxTick = new AtomicLong();
    private final AtomicLong version = new AtomicLong();

    private volatile MemberIndex cachedIndex = MemberIndex.EMPTY;
    private volatile long cachedIndexVersion = -1;

    public synchronized void updateMember(long keyId, long userId, String gameUsername, long tick, PlayerState state) {
        members.put(keyId, new MemberSnapshot(keyId, userId, gameUsername, tick, state, Instant.now()));
        long max = maxTick.updateAndGet(t -> Math.max(t, tick));

        Set<Cell> visibleResources = new HashSet<>();
        Set<Cell> currentVisibleCells = visibleCells(state);
        if (state.objects() != null) {
            for (GameObject o : state.objects()) {
                String kind = o.kind() == null ? "" : o.kind();
                switch (kind) {
                    case "OBSTACLE" -> {
                        if (o.positions() != null) {
                            for (Cell c : o.positions()) {
                                rememberObstacle(c, true);
                            }
                        }
                    }
                    case "RESOURCE" -> {
                        if (o.positions() != null) {
                            visibleResources.addAll(o.positions());
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
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        // 完整 state 中，当前明确可见的格子若没有 RESOURCE，就说明该资源已被采集或消失。
        resources.keySet().removeIf(c -> currentVisibleCells.contains(c) && !visibleResources.contains(c));
        rebuildVisibleEnemies();

        if (state.beacon() != null) {
            beacon = state.beacon();
        }
        objectOwners.values().removeIf(o -> o.lastTick() < max - 40);
        version.incrementAndGet();
    }

    public synchronized void removeMember(long keyId) {
        members.remove(keyId);
        rebuildVisibleEnemies();
        version.incrementAndGet();
    }

    private void rebuildVisibleEnemies() {
        Map<String, EnemySighting> rebuilt = new HashMap<>();
        Set<String> allianceObjectIds = new HashSet<>();

        // 同一个联盟对象在其他成员的 state 中会以 controlled=false 出现，先收集全部己方对象 ID。
        for (MemberSnapshot member : members.values()) {
            if (member.state() == null || member.state().objects() == null) continue;
            for (GameObject o : member.state().objects()) {
                if (o.isControlled() && o.id() != null) {
                    allianceObjectIds.add(o.id());
                }
            }
        }
        for (MemberSnapshot member : members.values()) {
            if (member.state() == null || member.state().objects() == null) continue;
            for (GameObject o : member.state().objects()) {
                if ((o.isCore() || o.isUnit()) && !o.isControlled()
                        && o.id() != null && !allianceObjectIds.contains(o.id())) {
                    EnemySighting sighting = new EnemySighting(o.id(), o.kind(), o.unitType(),
                            o.ownerUsername(), o.position(), o.hp(), o.shield(), member.tick());
                    rebuilt.merge(o.id(), sighting,
                            (oldValue, newValue) -> newValue.tick() >= oldValue.tick() ? newValue : oldValue);
                }
            }
        }
        enemies.clear();
        enemies.putAll(rebuilt);
    }

    /**
     * 计算该玩家当前完整 state 能明确观察到的格子。服务端已经给出了可见障碍，
     * 按官方整数 supercover 视线规则处理遮挡；障碍格本身可见，障碍后的格子不可见。
     */
    private Set<Cell> visibleCells(PlayerState state) {
        Set<Cell> visible = new HashSet<>();
        if (state.objects() == null) return visible;

        Set<Cell> visibleObstacles = new HashSet<>();
        for (GameObject o : state.objects()) {
            if ("OBSTACLE".equals(o.kind()) && o.positions() != null) {
                visibleObstacles.addAll(o.positions());
            }
        }
        for (GameObject o : state.objects()) {
            if (!o.isControlled() || o.position() == null) continue;
            int radius = o.visionRadius();
            Cell origin = o.position();
            for (long dx = -radius; dx <= radius; dx++) {
                long remaining = radius - Math.abs(dx);
                for (long dy = -remaining; dy <= remaining; dy++) {
                    Cell target = new Cell(origin.x() + dx, origin.y() + dy);
                    if (hasLineOfSight(origin, target, visibleObstacles)) {
                        visible.add(target);
                    }
                }
            }
        }
        return visible;
    }

    /** 整数 supercover：线穿过格角时两侧格都参与遮挡判断。 */
    private static boolean hasLineOfSight(Cell from, Cell to, Set<Cell> obstacles) {
        if (from.equals(to)) return true;
        long dx = to.x() - from.x();
        long dy = to.y() - from.y();
        long nx = Math.abs(dx);
        long ny = Math.abs(dy);
        long signX = Long.compare(dx, 0);
        long signY = Long.compare(dy, 0);
        long x = from.x();
        long y = from.y();
        long ix = 0;
        long iy = 0;

        while (ix < nx || iy < ny) {
            long lhs = (1 + 2 * ix) * ny;
            long rhs = (1 + 2 * iy) * nx;
            if (lhs == rhs) {
                // 正好穿过格角：任意一侧有障碍都挡住后方；目标障碍本身仍可见。
                Cell sideX = new Cell(x + signX, y);
                Cell sideY = new Cell(x, y + signY);
                if ((!sideX.equals(to) && obstacles.contains(sideX))
                        || (!sideY.equals(to) && obstacles.contains(sideY))) {
                    return false;
                }
                x += signX;
                y += signY;
                ix++;
                iy++;
            } else if (lhs < rhs) {
                x += signX;
                ix++;
            } else {
                y += signY;
                iy++;
            }
            Cell cell = new Cell(x, y);
            if (cell.equals(to)) return true;
            if (obstacles.contains(cell)) return false;
        }
        return true;
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

    public List<TerrainChange> drainTerrainChanges(int max) {
        List<TerrainChange> out = new ArrayList<>();
        TerrainChange change;
        while (out.size() < max && (change = pendingTerrainChanges.poll()) != null) {
            out.add(change);
        }
        return out;
    }

    public synchronized TerrainWindow terrainWindow(long minChunkX, long maxChunkX,
                                                     long minChunkY, long maxChunkY) {
        List<TerrainChunk> chunks = new ArrayList<>();
        obstacleChunks.forEach((key, bits) -> {
            if (key.x() >= minChunkX && key.x() <= maxChunkX
                    && key.y() >= minChunkY && key.y() <= maxChunkY) {
                chunks.add(new TerrainChunk(key.x(), key.y(), (BitSet) bits.clone()));
            }
        });
        chunks.sort((left, right) -> {
            int byY = Long.compare(left.chunkY(), right.chunkY());
            return byY != 0 ? byY : Long.compare(left.chunkX(), right.chunkX());
        });
        return new TerrainWindow(terrainEpoch, terrainRevision.get(), List.copyOf(chunks));
    }

    public String terrainEpoch() {
        return terrainEpoch;
    }

    public long terrainRevision() {
        return terrainRevision.get();
    }

    /** 启动时从持久层恢复障碍记忆 */
    public synchronized void preloadObstacles(Iterable<Cell> cells) {
        for (Cell c : cells) {
            rememberObstacle(c, false);
        }
        version.incrementAndGet();
    }

    private void rememberObstacle(Cell cell, boolean publishChange) {
        if (cell == null || obstacles.putIfAbsent(cell, Boolean.TRUE) != null) {
            return;
        }
        long chunkX = Math.floorDiv(cell.x(), TERRAIN_CHUNK_SIZE);
        long chunkY = Math.floorDiv(cell.y(), TERRAIN_CHUNK_SIZE);
        int localX = Math.floorMod(cell.x(), TERRAIN_CHUNK_SIZE);
        int localY = Math.floorMod(cell.y(), TERRAIN_CHUNK_SIZE);
        obstacleChunks.computeIfAbsent(new TerrainChunkKey(chunkX, chunkY), ignored -> new BitSet(1024))
                .set(localY * TERRAIN_CHUNK_SIZE + localX);
        if (publishChange) {
            pendingObstacles.add(cell);
            long revision = terrainRevision.incrementAndGet();
            pendingTerrainChanges.add(new TerrainChange(revision, cell));
        }
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
