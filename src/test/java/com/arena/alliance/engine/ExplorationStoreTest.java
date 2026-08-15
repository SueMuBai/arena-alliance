package com.arena.alliance.engine;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExplorationStoreTest {

    @Test
    void negativeCoordinatesUseFloorBasedChunkAndBitIndex() {
        Cell corner = new Cell(-1, -1);
        assertEquals(new ChunkCoord(-1, -1), ChunkCoord.of(corner));
        assertEquals(ChunkCoord.CELL_COUNT - 1, ChunkCoord.localIndex(corner));
        assertEquals(corner, new ChunkCoord(-1, -1)
                .cell(ChunkCoord.CELL_COUNT - 1).orElseThrow());
    }

    @Test
    void visibleCoverageCanBeDrainedAndRequeuedWithoutLoss() {
        WorldAggregator aggregator = new WorldAggregator();
        GameObject core = new GameObject("CORE", "core", true, "alice", new Cell(-1, -1),
                5, 5, "NORMAL", null, null, null);
        aggregator.updateMember(1, 10, "alice", 100,
                new PlayerState("ACTIVE", null, 0, 0, null, List.of(core), List.of()));

        List<WorldAggregator.CoverageUpdate> first = aggregator.drainPendingCoverage(10);
        assertTrue(first.stream().anyMatch(update -> update.coord().equals(new ChunkCoord(-1, -1))));
        WorldAggregator.CoverageUpdate update = first.stream()
                .filter(item -> item.coord().equals(new ChunkCoord(-1, -1))).findFirst().orElseThrow();
        BitSet bits = BitSet.valueOf(update.bytes());
        assertTrue(bits.get(ChunkCoord.localIndex(new Cell(-1, -1))));

        aggregator.requeueCoverage(first);
        List<WorldAggregator.CoverageUpdate> retried = aggregator.drainPendingCoverage(10);
        WorldAggregator.CoverageUpdate retry = retried.stream()
                .filter(item -> item.coord().equals(update.coord())).findFirst().orElseThrow();
        assertArrayEquals(update.bytes(), retry.bytes());
        assertEquals(update.updatedTick(), retry.updatedTick());
    }

    @Test
    void persistenceFailureRequeuesTheDrainedBatch() {
        ExploredChunkRepository repository = mock(ExploredChunkRepository.class);
        WorldAggregator aggregator = mock(WorldAggregator.class);
        WorldAggregator.CoverageUpdate update = new WorldAggregator.CoverageUpdate(
                new ChunkCoord(2, -3), new byte[128], 77);
        List<WorldAggregator.CoverageUpdate> pending = List.of(update);
        org.mockito.Mockito.when(aggregator.drainPendingCoverage(500)).thenReturn(pending);
        doThrow(new RuntimeException("db unavailable")).when(repository).saveAll(anyList());

        new ExplorationStore(repository, aggregator).flush();

        verify(aggregator).requeueCoverage(pending);
    }

    @Test
    void obstaclePersistenceFailureAlsoRequeuesTheDrainedBatch() {
        WorldCellRepository repository = mock(WorldCellRepository.class);
        WorldAggregator aggregator = mock(WorldAggregator.class);
        List<Cell> pending = List.of(new Cell(-1, 2));
        org.mockito.Mockito.when(aggregator.drainPendingObstacles(2000)).thenReturn(pending);
        doThrow(new RuntimeException("db unavailable")).when(repository).saveAll(anyList());

        new ObstacleStore(repository, aggregator).flush();

        verify(aggregator).requeueObstacles(pending);
    }
}
