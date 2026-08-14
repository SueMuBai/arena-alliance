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

    /**
     * 曼哈顿视野半径；未知类型按 0 处理（协议新增兵种时保持容错）。
     * 协议中 unit_type 只在 UNIT 上出现，Core 的 unitType 为 null，需用 kind 查表。
     */
    public int visionRadius() {
        UnitType type = UnitType.fromName(isUnit() ? unitType : kind);
        return type == null ? 0 : type.visionRadius();
    }
}
