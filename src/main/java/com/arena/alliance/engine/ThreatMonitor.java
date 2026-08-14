package com.arena.alliance.engine;

import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.Cell;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 作战计划审查（纯函数）：找出计划中对其他联盟成员的攻击动作。
 * SWEEP：单位当前格 + 方向 = 目标格；SHOOT：expected_cell（可带 target_id 精确模式）。
 * 目标格/目标对象属于另一位成员 → 敌意动作。
 */
public final class ThreatMonitor {

    public record Offense(String unitId, String actionType, Cell targetCell, WorldAggregator.OwnerRef victim) {
    }

    private ThreatMonitor() {
    }

    public static List<Offense> inspect(long attackerUserId,
                                        ObjectNode plan,
                                        Map<String, Cell> attackerUnitPositions,
                                        WorldAggregator.MemberIndex index) {
        List<Offense> offenses = new ArrayList<>();
        JsonNode actions = plan.get("unit_actions");
        if (actions == null || !actions.isObject()) {
            return offenses;
        }
        Iterator<Map.Entry<String, JsonNode>> it = actions.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String unitId = entry.getKey();
            JsonNode action = entry.getValue();
            String type = action.path("type").asText("");
            switch (type) {
                case "SWEEP" -> {
                    Cell from = attackerUnitPositions.get(unitId);
                    if (from == null) break;
                    Cell target = from.move(action.path("direction").asText(""));
                    WorldAggregator.OwnerRef victim = index.byCell().get(target);
                    if (victim != null && victim.userId() != attackerUserId) {
                        offenses.add(new Offense(unitId, type, target, victim));
                    }
                }
                case "SHOOT" -> {
                    Cell target = GameJson.parseCell(action.get("expected_cell"));
                    WorldAggregator.OwnerRef victim = null;
                    JsonNode targetIdNode = action.get("target_id");
                    if (targetIdNode != null && !targetIdNode.isNull()) {
                        victim = index.byId().get(targetIdNode.asText());
                    }
                    if (victim == null && target != null) {
                        victim = index.byCell().get(target);
                    }
                    if (victim != null && victim.userId() != attackerUserId) {
                        offenses.add(new Offense(unitId, type, target, victim));
                    }
                }
                default -> {
                }
            }
        }
        return offenses;
    }
}
