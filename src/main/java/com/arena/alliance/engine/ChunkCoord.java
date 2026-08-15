package com.arena.alliance.engine;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GridMath;

import java.util.Optional;

/** 32×32 世界分块坐标，保留完整 int64 范围，不做 32 位截断打包。 */
public record ChunkCoord(long x, long y) {

    public static final int SIZE = 32;
    public static final int CELL_COUNT = SIZE * SIZE;

    public static ChunkCoord of(Cell cell) {
        return new ChunkCoord(Math.floorDiv(cell.x(), SIZE), Math.floorDiv(cell.y(), SIZE));
    }

    public static int localIndex(Cell cell) {
        int localX = Math.floorMod(cell.x(), SIZE);
        int localY = Math.floorMod(cell.y(), SIZE);
        return localY * SIZE + localX;
    }

    public Optional<Cell> cell(int index) {
        if (index < 0 || index >= CELL_COUNT) return Optional.empty();
        try {
            long baseX = Math.multiplyExact(x, SIZE);
            long baseY = Math.multiplyExact(y, SIZE);
            return GridMath.offset(new Cell(baseX, baseY), index % SIZE, index / SIZE);
        } catch (ArithmeticException ignored) {
            return Optional.empty();
        }
    }

    public String key() {
        return x + ":" + y;
    }
}
