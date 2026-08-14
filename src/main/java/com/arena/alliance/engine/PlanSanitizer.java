package com.arena.alliance.engine;

import com.arena.alliance.game.GameJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;

/**
 * 反制计划生成（纯函数）：在攻击者原计划的深拷贝上，
 * 仅把敌意动作替换为 WAIT，其余动作（采集、移动、生产……）原样保留，
 * 尽量不影响攻击者的正常运营，也避免整计划清空造成额外伤害。
 */
public final class PlanSanitizer {

    private PlanSanitizer() {
    }

    public static ObjectNode sanitize(ObjectNode plan, Collection<String> offendingUnitIds) {
        ObjectNode copy = plan.deepCopy();
        JsonNode actions = copy.get("unit_actions");
        if (actions instanceof ObjectNode obj) {
            for (String unitId : offendingUnitIds) {
                ObjectNode wait = GameJson.MAPPER.createObjectNode();
                wait.put("type", "WAIT");
                obj.set(unitId, wait);
            }
        }
        return copy;
    }
}
