package com.arena.alliance.game.dto;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 兵种基础属性（对应协议 UnitType 枚举值：WORKER/VANGUARD/RANGER）。
 * 传输层字段仍保留字符串以容忍协议新增兵种，需要属性时经 fromName 解析。
 */
public enum UnitType {
    /** 核心 */
    CORE(5, 5),
    /** 工人 */
    WORKER(3, 2),
    /** 先锋 */
    VANGUARD(4, 4),
    /** 游侠 */
    RANGER(5, 2);

    private final int visionRadius;
    private final int maxHp;

    private static final Map<String, UnitType> nameByTypeMap = new HashMap<>();

    static {
        Arrays.stream(values()).forEach(type -> nameByTypeMap.put(type.name(), type));
    }

    UnitType(int visionRadius, int maxHp) {
        this.visionRadius = visionRadius;
        this.maxHp = maxHp;
    }

    /** 曼哈顿视野半径（官方 Map and vision 视野表） */
    public int visionRadius() {
        return visionRadius;
    }

    /** 满血值（官方 Units 数值表；Core 为 5） */
    public int maxHp() {
        return maxHp;
    }

    public boolean isCurType(String name) {
        return name != null && name.equals(name());
    }

    /** @return 未知兵种返回 null，调用方需容错 */
    public static UnitType fromName(String name) {
       return nameByTypeMap.get(name);
    }
}
