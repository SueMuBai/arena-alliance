package com.arena.alliance.hosting.plan;

import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.game.dto.UnitType;
import com.arena.alliance.hosting.HostingConfig;
import com.arena.alliance.hosting.ManualTargeting;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;

/**
 * 生产/Core 动作编译（参考 agent 的阶段与价格档）：
 * 阵容阶段 12W → 补满 V → 补满 R → 至 workerTarget；
 * 动态价格 round_half_up(base × 1.3^k)，k = max(0, floor((pop-20)/5)+1)，base W5/V10/R12。
 */
public final class ProductionPlanner {

    /** Core 满血/满盾均为 5（官方数值表） */
    static final int CORE_MAX_HP = 5;
    /** 生产后保留的应急预算（治疗/修盾） */
    static final int EMERGENCY_RESERVE = 2;

    private ProductionPlanner() {
    }

    public static long unitCost(UnitType type, int population) {
        long base = switch (type) {
            case WORKER -> 5;
            case VANGUARD -> 10;
            case RANGER -> 12;
            case CORE -> Long.MAX_VALUE;
        };
        int k = Math.max(0, Math.floorDiv(population - 20, 5) + 1);
        if (k > 200) return Long.MAX_VALUE;
        BigInteger numerator = BigInteger.valueOf(base).multiply(BigInteger.valueOf(13).pow(k));
        BigInteger denominator = BigInteger.TEN.pow(k);
        BigInteger rounded = numerator.shiftLeft(1).add(denominator)
                .divide(denominator.shiftLeft(1));
        return rounded.bitLength() > 63 ? Long.MAX_VALUE : rounded.longValue();
    }

    /** 阶段化补兵：先 12 Worker 立经济，再补齐防御 V/R，最后扩到目标 Worker 数 */
    public static UnitType nextSpawnType(int workers, int vanguards, int rangers, HostingConfig cfg) {
        if (workers < Math.min(12, cfg.workerTarget())) {
            return UnitType.WORKER;
        }
        if (vanguards < cfg.vanguardTarget()) {
            return UnitType.VANGUARD;
        }
        if (rangers < cfg.rangerTarget()) {
            return UnitType.RANGER;
        }
        if (workers < cfg.workerTarget()) {
            return UnitType.WORKER;
        }
        return null;
    }

    /**
     * Core 动作：生产 > 治疗 > 修盾 > 无动作。
     *
     * @param plannedHealSpend 本 Tick 已计划的单位治疗开销（占用预算）
     * @return core_action 节点；null 表示不下发（缺省 WAIT）
     */
    public static ObjectNode coreAction(PlayerState state, HostingConfig cfg, long plannedHealSpend) {
        GameObject core = state.controlledCore();
        if (core == null || core.position() == null || !"NORMAL".equals(core.state())) {
            return null;
        }
        int workers = 0, vanguards = 0, rangers = 0;
        for (GameObject o : state.objects()) {
            if (o.isUnit() && o.isControlled()) {
                switch (UnitType.fromName(o.unitType())) {
                    case WORKER -> workers++;
                    case VANGUARD -> vanguards++;
                    case RANGER -> rangers++;
                    default -> {
                    }
                }
            }
        }
        long available = state.resources() - plannedHealSpend;

        int occupantsOnCore = 0;
        for (GameObject object : state.objects()) {
            if ((object.isCore() || object.isUnit()) && core.position().equals(object.position())) {
                occupantsOnCore++;
            }
        }

        UnitType spawn = nextSpawnType(workers, vanguards, rangers, cfg);
        if (spawn != null && occupantsOnCore < 2) {
            long cost = unitCost(spawn, state.population());
            if (available >= cost + EMERGENCY_RESERVE) {
                ObjectNode node = GameJson.MAPPER.createObjectNode();
                node.put("type", "SPAWN");
                node.put("unit_type", spawn.name());
                return node;
            }
        }
        if (core.hp() != null && core.hp() < CORE_MAX_HP && available > 0) {
            ObjectNode node = GameJson.MAPPER.createObjectNode();
            node.put("type", "HEAL");
            return node;
        }
        if (core.shield() != null && core.shield() < ManualTargeting.coreShieldCap(state)
                && available > EMERGENCY_RESERVE) {
            ObjectNode node = GameJson.MAPPER.createObjectNode();
            node.put("type", "REPAIR_SHIELD");
            return node;
        }
        return null;
    }
}
