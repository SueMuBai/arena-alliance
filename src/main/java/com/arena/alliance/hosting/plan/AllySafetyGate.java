package com.arena.alliance.hosting.plan;

import com.arena.alliance.engine.PlanSanitizer;
import com.arena.alliance.engine.ThreatMonitor;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * 盟友安全闸（红线，双保险的最后一道）：
 * 指挥管线不应产出攻击盟友的动作，但无论上游如何演化，
 * 提交前一律用 ThreatMonitor 复查并剔除任何指向盟友的动作。
 */
public final class AllySafetyGate {

    private AllySafetyGate() {
    }

    public static ObjectNode enforce(long accountUserId, ObjectNode plan,
                                     Map<String, Cell> ownUnitPositions,
                                     WorldAggregator.MemberIndex allyIndex) {
        List<ThreatMonitor.Offense> offenses =
                ThreatMonitor.inspect(accountUserId, plan, ownUnitPositions, allyIndex);
        if (offenses.isEmpty()) {
            return plan;
        }
        return PlanSanitizer.sanitize(plan,
                offenses.stream().map(ThreatMonitor.Offense::unitId).toList());
    }
}
