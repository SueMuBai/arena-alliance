package com.arena.alliance.map;

import com.arena.alliance.common.AllianceException;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTerrainServiceTest {

    @Test
    void windowUsesFixedSizeBase64Bitsets() {
        WorldAggregator aggregator = new WorldAggregator();
        aggregator.preloadObstacles(List.of(new Cell(0, 0), new Cell(31, 31)));
        MapTerrainService service = new MapTerrainService(aggregator);

        MapTerrainService.Window window = service.window(0, 0, 0, 0);

        assertEquals(32, window.chunkSize());
        assertEquals(1, window.chunks().size());
        byte[] bits = Base64.getDecoder().decode(window.chunks().getFirst().bits());
        assertEquals(128, bits.length);
        assertTrue((bits[0] & 1) != 0);
        assertTrue((bits[127] & 0x80) != 0);
    }

    @Test
    void rejectsUnboundedTerrainWindows() {
        MapTerrainService service = new MapTerrainService(new WorldAggregator());

        assertThrows(AllianceException.class, () -> service.window(0, 2048, 0, 0));
        assertThrows(AllianceException.class, () -> service.window(1, 0, 0, 0));
    }
}
