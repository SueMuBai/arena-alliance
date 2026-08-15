package com.arena.alliance.game;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GridMath;
import com.arena.alliance.hosting.plan.Pathfinder;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GridMathTest {

    @Test
    void offsetsAndMovesDoNotWrapAtInt64Boundary() {
        Cell max = new Cell(Long.MAX_VALUE, Long.MAX_VALUE);
        Cell min = new Cell(Long.MIN_VALUE, Long.MIN_VALUE);

        assertFalse(GridMath.offset(max, 1, 0).isPresent());
        assertFalse(GridMath.offset(min, -1, 0).isPresent());
        assertEquals(max, max.move("RIGHT"));
        assertEquals(min, min.move("UP"));
        assertEquals(Long.MAX_VALUE, GridMath.manhattan(max, min));
    }

    @Test
    void pathfinderChoosesRealDirectionAcrossHugeDistanceWithoutWrapping() {
        assertEquals("LEFT", Pathfinder.nextStep(
                new Cell(Long.MAX_VALUE, 0), new Cell(Long.MIN_VALUE, 0), Set.of(), Set.of()));
        assertEquals("RIGHT", Pathfinder.nextStep(
                new Cell(Long.MIN_VALUE, 0), new Cell(Long.MAX_VALUE, 0), Set.of(), Set.of()));
    }
}
