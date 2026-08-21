package com.arena.alliance.map;

import com.arena.alliance.common.AllianceException;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 永久障碍地形的分块传输层。动态地图快照不再携带全量障碍；客户端按视口获取区块，
 * 已连接客户端只接收新发现障碍的增量。
 */
@Service
public class MapTerrainService {

    private static final int CHUNK_BYTES = WorldAggregator.TERRAIN_CHUNK_SIZE
            * WorldAggregator.TERRAIN_CHUNK_SIZE / Byte.SIZE;
    private static final long MAX_CHUNK_SPAN = 2048;

    public record Chunk(long x, long y, String bits) {
    }

    public record Window(String epoch, long revision, int chunkSize, List<Chunk> chunks) {
    }

    public record Delta(String epoch, long fromRevision, long toRevision, List<long[]> cells) {
    }

    private final WorldAggregator aggregator;

    public MapTerrainService(WorldAggregator aggregator) {
        this.aggregator = aggregator;
    }

    public Window window(long minChunkX, long maxChunkX, long minChunkY, long maxChunkY) {
        validateWindow(minChunkX, maxChunkX, minChunkY, maxChunkY);
        WorldAggregator.TerrainWindow source = aggregator.terrainWindow(
                minChunkX, maxChunkX, minChunkY, maxChunkY);
        List<Chunk> chunks = source.chunks().stream()
                .map(chunk -> new Chunk(chunk.chunkX(), chunk.chunkY(), encode(chunk.cells().toByteArray())))
                .toList();
        return new Window(source.epoch(), source.revision(), WorldAggregator.TERRAIN_CHUNK_SIZE, chunks);
    }

    public Delta delta(List<WorldAggregator.TerrainChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }
        List<long[]> cells = new ArrayList<>(changes.size());
        for (WorldAggregator.TerrainChange change : changes) {
            Cell cell = change.cell();
            cells.add(new long[]{cell.x(), cell.y()});
        }
        return new Delta(aggregator.terrainEpoch(), changes.getFirst().revision(),
                changes.getLast().revision(), List.copyOf(cells));
    }

    private static String encode(byte[] source) {
        byte[] fixed = new byte[CHUNK_BYTES];
        System.arraycopy(source, 0, fixed, 0, Math.min(source.length, fixed.length));
        return Base64.getEncoder().encodeToString(fixed);
    }

    private static void validateWindow(long minChunkX, long maxChunkX, long minChunkY, long maxChunkY) {
        if (minChunkX > maxChunkX || minChunkY > maxChunkY) {
            throw AllianceException.badRequest("地形区块范围无效");
        }
        if (span(minChunkX, maxChunkX) > MAX_CHUNK_SPAN || span(minChunkY, maxChunkY) > MAX_CHUNK_SPAN) {
            throw AllianceException.badRequest("地形区块范围过大");
        }
    }

    private static long span(long min, long max) {
        try {
            return Math.addExact(Math.subtractExact(max, min), 1);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
