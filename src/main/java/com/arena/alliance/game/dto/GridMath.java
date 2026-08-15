package com.arena.alliance.game.dto;

import java.util.Optional;

/**
 * 世界坐标的安全运算。所有距离都饱和到 Long.MAX_VALUE，避免 int64 边界回绕。
 */
public final class GridMath {

    /** JavaScript Number 能精确表示的最大整数。 */
    public static final long JS_MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private GridMath() {
    }

    public static Optional<Cell> offset(Cell from, long dx, long dy) {
        if (from == null) return Optional.empty();
        try {
            return Optional.of(new Cell(Math.addExact(from.x(), dx), Math.addExact(from.y(), dy)));
        } catch (ArithmeticException ignored) {
            return Optional.empty();
        }
    }

    public static long manhattan(Cell a, Cell b) {
        if (a == null || b == null) return Long.MAX_VALUE;
        return saturatedAdd(absDifference(a.x(), b.x()), absDifference(a.y(), b.y()));
    }

    public static long chebyshev(Cell a, Cell b) {
        if (a == null || b == null) return Long.MAX_VALUE;
        return Math.max(axisDistance(a.x(), b.x()), axisDistance(a.y(), b.y()));
    }

    /** 单轴绝对距离；真实差值超出 int64 时饱和，绝不回绕。 */
    public static long axisDistance(long a, long b) {
        return absDifference(a, b);
    }

    public static boolean isWebSafe(Cell cell) {
        return cell != null && isWebSafe(cell.x()) && isWebSafe(cell.y());
    }

    public static boolean isWebSafe(long value) {
        return value >= -JS_MAX_SAFE_INTEGER && value <= JS_MAX_SAFE_INTEGER;
    }

    private static long absDifference(long a, long b) {
        try {
            long delta = Math.subtractExact(a, b);
            return delta == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(delta);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long a, long b) {
        if (Long.MAX_VALUE - a < b) return Long.MAX_VALUE;
        return a + b;
    }
}
