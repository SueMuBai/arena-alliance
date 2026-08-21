package com.arena.alliance.engine;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAggregatorTerrainTest {

    @Test
    void terrainChunksUseFloorCoordinatesForNegativeCells() {
        WorldAggregator aggregator = new WorldAggregator();
        aggregator.preloadObstacles(List.of(
                new Cell(-1, -1), new Cell(0, 0), new Cell(31, 31), new Cell(32, 0)));

        WorldAggregator.TerrainWindow window = aggregator.terrainWindow(-1, 1, -1, 0);
        Map<String, WorldAggregator.TerrainChunk> chunks = window.chunks().stream()
                .collect(Collectors.toMap(c -> c.chunkX() + ":" + c.chunkY(), Function.identity()));

        assertEquals(3, chunks.size());
        assertTrue(chunks.get("-1:-1").cells().get(1023));
        assertTrue(chunks.get("0:0").cells().get(0));
        assertTrue(chunks.get("0:0").cells().get(1023));
        assertTrue(chunks.get("1:0").cells().get(0));
        assertEquals(0, window.revision());
    }

    @Test
    void newlyDiscoveredObstacleProducesOneOrderedDelta() {
        WorldAggregator aggregator = new WorldAggregator();
        Cell cell = new Cell(65, -2);
        PlayerState state = new PlayerState("ACTIVE", null, 0, 0, null,
                List.of(obstacles(cell, cell)), List.of());

        aggregator.updateMember(1L, 2L, "alice", 100, state);

        assertEquals(1, aggregator.terrainRevision());
        List<WorldAggregator.TerrainChange> changes = aggregator.drainTerrainChanges(10);
        assertEquals(1, changes.size());
        assertEquals(1, changes.getFirst().revision());
        assertEquals(cell, changes.getFirst().cell());
        assertTrue(aggregator.drainTerrainChanges(10).isEmpty());
    }

    private static GameObject obstacles(Cell... cells) {
        return new GameObject("OBSTACLE", null, null, null, null,
                null, null, null, null, null, List.of(cells));
    }
}
