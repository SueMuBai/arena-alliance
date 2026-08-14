package com.arena.alliance.game.dto;

/**
 * 世界坐标（int64）。y 轴向下。
 */
public record Cell(long x, long y) {

    public Cell move(String direction) {
        return switch (direction) {
            case "UP" -> new Cell(x, y - 1);
            case "DOWN" -> new Cell(x, y + 1);
            case "LEFT" -> new Cell(x - 1, y);
            case "RIGHT" -> new Cell(x + 1, y);
            default -> this;
        };
    }

    @Override
    public String toString() {
        return "[" + x + "," + y + "]";
    }
}
