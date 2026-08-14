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
    CORE(5),
    /** 工人 */
    WORKER(3),
    /** 先锋 */
    VANGUARD(4),
    /** 游侠 */
    RANGER(5);

    private final int visionRadius;

    private static final Map<String, UnitType> nameByTypeMap = new HashMap<>();

    static {
        Arrays.stream(values()).forEach(type -> nameByTypeMap.put(type.name(), type));
    }

    UnitType(int visionRadius) {
        this.visionRadius = visionRadius;
    }

    /** 曼哈顿视野半径（官方 Map and vision 视野表） */
    public int visionRadius() {
        return visionRadius;
    }

    public boolean isCurType(String name) {
        return name != null && name.equals(name());
    }

    /** @return 未知兵种返回 null，调用方需容错 */
    public static UnitType fromName(String name) {
       return nameByTypeMap.get(name);
    }
}
