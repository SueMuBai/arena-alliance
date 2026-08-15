package com.arena.alliance.hosting;

import com.arena.alliance.game.dto.UnitType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.arena.alliance.game.dto.GridMath.JS_MAX_SAFE_INTEGER;

/**
 * 手动接管的动作校验（纯函数）：严格按官方命令协议校验动作形状与兵种限制。
 * 协议规定"动作只能包含其 type 所列字段"，多余字段会导致整份计划被拒，
 * 因此这里在提交前就拦下来，避免连累指挥官的其他动作。
 */
public final class ManualControl {

    public record Validation(boolean ok, String message) {

        public static final Validation OK = new Validation(true, null);

        public static Validation fail(String message) {
            return new Validation(false, message);
        }
    }

    /** 单位可用动作 → 除 type 外允许出现的字段 */
    private static final java.util.Map<String, Set<String>> UNIT_ACTIONS = java.util.Map.of(
            "WAIT", Set.of(),
            "MOVE", Set.of("direction"),
            "HARVEST", Set.of(),
            "DEPOSIT", Set.of(),
            "SWEEP", Set.of("direction"),
            "SHOOT", Set.of("expected_cell", "target_id"),
            "PICKUP_BEACON", Set.of(),
            "DROP_BEACON", Set.of(),
            "HEAL", Set.of(),
            "SELF_DESTRUCT", Set.of());

    /** Core 可用动作 → 除 type 外允许出现的字段 */
    private static final java.util.Map<String, Set<String>> CORE_ACTIONS = java.util.Map.of(
            "WAIT", Set.of(),
            "SPAWN", Set.of("unit_type"),
            "HEAL", Set.of(),
            "REPAIR_SHIELD", Set.of(),
            "START_MOVE", Set.of("direction"),
            "CANCEL_MOVE", Set.of(),
            "PICKUP_BEACON", Set.of(),
            "DROP_BEACON", Set.of(),
            "SELF_DESTRUCT", Set.of());

    private static final Set<String> DIRECTIONS = Set.of("UP", "DOWN", "LEFT", "RIGHT");

    private ManualControl() {
    }

    /**
     * 校验动作是否可由该对象执行。
     *
     * @param isCore   目标是否为 Core
     * @param unitType 单位兵种（Core 传 null）
     */
    public static Validation validate(boolean isCore, String unitType, JsonNode action) {
        if (action == null || !action.isObject()) {
            return Validation.fail("动作格式错误");
        }
        String type = action.path("type").asText(null);
        if (type == null || type.isBlank()) {
            return Validation.fail("动作缺少 type");
        }
        Set<String> allowedFields = isCore ? CORE_ACTIONS.get(type) : UNIT_ACTIONS.get(type);
        if (allowedFields == null) {
            return Validation.fail((isCore ? "Core" : "单位") + "不支持动作 " + type);
        }
        // 协议：动作只能包含它自己那一行列出的字段
        Iterator<String> names = action.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!"type".equals(field) && !allowedFields.contains(field)) {
                return Validation.fail("动作 " + type + " 不允许字段 " + field);
            }
        }
        if (!isCore) {
            Validation typeCheck = validateUnitTypeCapability(type, unitType);
            if (!typeCheck.ok()) {
                return typeCheck;
            }
        }
        return validateFields(type, action);
    }

    private static Validation validateUnitTypeCapability(String type, String unitType) {
        UnitType unit = UnitType.fromName(unitType);
        return switch (type) {
            case "HARVEST", "DEPOSIT" -> unit == UnitType.WORKER
                    ? Validation.OK : Validation.fail("只有 Worker 能执行 " + type);
            case "SWEEP" -> unit == UnitType.VANGUARD
                    ? Validation.OK : Validation.fail("只有 Vanguard 能执行扫击");
            case "SHOOT" -> unit == UnitType.RANGER
                    ? Validation.OK : Validation.fail("只有 Ranger 能执行射击");
            default -> Validation.OK;
        };
    }

    private static Validation validateFields(String type, JsonNode action) {
        switch (type) {
            case "MOVE", "SWEEP", "START_MOVE" -> {
                String direction = action.path("direction").asText(null);
                if (direction == null || !DIRECTIONS.contains(direction)) {
                    return Validation.fail("方向必须是 UP/DOWN/LEFT/RIGHT");
                }
            }
            case "SHOOT" -> {
                JsonNode cell = action.get("expected_cell");
                if (cell == null || !cell.isArray() || cell.size() != 2
                        || !webSafeInteger(cell.get(0)) || !webSafeInteger(cell.get(1))) {
                    return Validation.fail("射击目标格必须是 [x, y]");
                }
                JsonNode targetId = action.get("target_id");
                if (targetId != null) {
                    if (!targetId.isTextual() || !isUuid(targetId.asText())) {
                        return Validation.fail("射击 target_id 必须是非零、规范小写 UUID");
                    }
                }
            }
            case "SPAWN" -> {
                String unitType = action.path("unit_type").asText(null);
                if (!List.of("WORKER", "VANGUARD", "RANGER").contains(unitType)) {
                    return Validation.fail("生产类型必须是 WORKER/VANGUARD/RANGER");
                }
            }
            default -> {
            }
        }
        return Validation.OK;
    }

    private static boolean webSafeInteger(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) return false;
        long n = value.longValue();
        return n >= -JS_MAX_SAFE_INTEGER && n <= JS_MAX_SAFE_INTEGER;
    }

    private static boolean isUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return !parsed.equals(new UUID(0, 0)) && parsed.toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
