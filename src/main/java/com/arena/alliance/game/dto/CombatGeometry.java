package com.arena.alliance.game.dto;

import java.util.function.Predicate;

/** Arena Hero 攻击几何：Vanguard 正交相邻，Ranger 八方向 1-3 格且中间不得有障碍。 */
public final class CombatGeometry {

    private CombatGeometry() {
    }

    public static boolean canSweep(Cell from, Cell target) {
        return GridMath.manhattan(from, target) == 1;
    }

    public static boolean canShoot(Cell from, Cell target, Predicate<Cell> isObstacle) {
        if (from == null || target == null) return false;
        long dx;
        long dy;
        try {
            dx = Math.subtractExact(target.x(), from.x());
            dy = Math.subtractExact(target.y(), from.y());
        } catch (ArithmeticException ignored) {
            return false;
        }
        long ax = Math.abs(dx);
        long ay = Math.abs(dy);
        long range = Math.max(ax, ay);
        if (range < 1 || range > 3 || !((dx == 0) || (dy == 0) || ax == ay)) {
            return false;
        }
        long stepX = Long.signum(dx);
        long stepY = Long.signum(dy);
        for (long step = 1; step < range; step++) {
            Cell intermediate = GridMath.offset(from, stepX * step, stepY * step).orElse(null);
            if (intermediate == null || isObstacle.test(intermediate)) return false;
        }
        return true;
    }
}
