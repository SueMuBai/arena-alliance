package com.arena.alliance.game.dto;

/**
 * 世界坐标（int64）。y 轴向下。
 */
public record Cell(long x, long y) {

    public Cell move(String direction) {
        long dx = "RIGHT".equals(direction) ? 1 : ("LEFT".equals(direction) ? -1 : 0);
        long dy = "DOWN".equals(direction) ? 1 : ("UP".equals(direction) ? -1 : 0);
        if (dx == 0 && dy == 0) return this;
        return GridMath.offset(this, dx, dy).orElse(this);
    }

    @Override
    public String toString() {
        return "[" + x + "," + y + "]";
    }
}
