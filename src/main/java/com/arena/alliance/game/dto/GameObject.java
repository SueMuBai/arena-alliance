package com.arena.alliance.game.dto;

import java.util.List;

/**
 * state.objects 中的一项。kind=CORE/UNIT 时 id/position 有值；
 * kind=OBSTACLE/RESOURCE 为批量对象，仅 positions 有值。
 * 服务器不发 null：缺失字段这里为 null。
 */
public record GameObject(
        String kind,
        String id,
        Boolean controlled,
        String ownerUsername,
        Cell position,
        Integer hp,
        Integer shield,
        String state,
        String unitType,
        Integer cargo,
        List<Cell> positions
) {
    public boolean isCore() {
        return "CORE".equals(kind);
    }

    public boolean isUnit() {
        return "UNIT".equals(kind);
    }

    public boolean isControlled() {
        return Boolean.TRUE.equals(controlled);
    }
}
