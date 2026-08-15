package com.arena.alliance.hosting.plan;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.UnitType;
import com.arena.alliance.hosting.PilotMode;
import com.arena.alliance.hosting.plan.PlanningModels.AccountView;
import com.arena.alliance.hosting.plan.PlanningModels.EnemyView;
import com.arena.alliance.hosting.plan.PlanningModels.PlanningInput;
import com.arena.alliance.hosting.plan.PlanningModels.StrikeMission;
import com.arena.alliance.hosting.plan.PlanningModels.TargetObservation;
import com.arena.alliance.hosting.plan.ThreatAssessor.Assessment;
import com.arena.alliance.hosting.plan.ThreatAssessor.ThreatLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 清野战役（跨账号集火，⚔CLEAR 模式的主战条令）。
 *
 * 安全契约（照搬参考 agent 的清野约束）：
 * 1. 只打"重复观测确认长期静止"的目标——移动过的目标一律降级，绝不追击；
 * 2. 打击组从 Core 出发不超过 48 格，被拉出 56 格即释放目标返程；
 * 3. 账号自身威胁 > ALERT 时不参与远征（先自卫）；
 * 4. 留守底线：每账号至少留 1 名战斗单位守家；
 * 5. 目标永远只从敌方对象中选，且最终计划仍过盟友安全闸（双保险）。
 */
public final class ClearDoctrine {

    /** 确认静止所需的连续观测次数 */
    static final int CONFIRM_SIGHTINGS = 3;
    /** 观测记录过期（Tick）：太久没见就重新确认 */
    static final int OBSERVATION_TTL = 30;
    /** 打击组出击半径（离本方 Core） */
    static final int STRIKE_RANGE = 48;
    /** 释放半径：被拉出这个距离就放弃返程 */
    static final int RELEASE_RANGE = 56;
    /** 每个目标最多编入的打击单位数（跨账号累计） */
    static final int MAX_STRIKERS_PER_TARGET = 3;
    /** 每账号必须留守的战斗单位数 */
    static final int MIN_HOME_GUARDS = 1;

    private ClearDoctrine() {
    }

    /**
     * 更新静止目标观测台账：同格再次出现则累加，位置变化则清零（移动目标不予确认）。
     */
    public static Map<String, TargetObservation> observe(PlanningInput in) {
        Map<String, TargetObservation> observations = new LinkedHashMap<>();
        for (EnemyView enemy : in.enemies()) {
            TargetObservation prior = in.priorObservations().get(enemy.id());
            int sightings = 1;
            if (prior != null && prior.pos().equals(enemy.pos())
                    && in.tick() - prior.lastSeenTick() <= OBSERVATION_TTL) {
                sightings = prior.sightings() + 1;
            }
            observations.put(enemy.id(), new TargetObservation(enemy.id(), enemy.pos(), sightings, in.tick()));
        }
        // 暂时失去视野的目标保留一段时间，避免刚出视野就丢失确认进度
        in.priorObservations().forEach((id, prior) -> {
            if (!observations.containsKey(id) && in.tick() - prior.lastSeenTick() <= OBSERVATION_TTL) {
                observations.put(id, prior);
            }
        });
        return observations;
    }

    /**
     * 编组打击任务：为已确认的静止目标，从符合条件的 CLEAR 模式账号里抽调战斗单位集火。
     *
     * @param threats 各账号当前威胁评估（自身受压则不外派）
     */
    public static Map<String, StrikeMission> allocate(PlanningInput in,
                                                      Map<String, TargetObservation> observations,
                                                      Map<Long, Assessment> threats,
                                                      List<String> journal) {
        Map<String, StrikeMission> missions = new LinkedHashMap<>();
        Map<String, Cell> enemyById = new HashMap<>();
        for (EnemyView enemy : in.enemies()) {
            enemyById.put(enemy.id(), enemy.pos());
        }

        // ① 续约：目标仍确认存在、单位仍在编且未被拉出释放半径
        for (AccountView account : in.accounts()) {
            if (!canJoinCampaign(account, threats)) {
                continue;
            }
            Map<String, GameObject> combatUnits = combatUnits(account);
            Cell corePos = corePos(account);
            for (Map.Entry<String, StrikeMission> e : in.priorStrikes().entrySet()) {
                StrikeMission prior = e.getValue();
                if (prior.keyId() != account.keyId()) {
                    continue;
                }
                GameObject unit = combatUnits.get(prior.unitId());
                Cell target = enemyById.get(prior.enemyId());
                if (unit == null || corePos == null) {
                    continue;
                }
                TargetObservation currentObservation = observations.get(prior.enemyId());
                if (target == null || currentObservation == null
                        || currentObservation.sightings() < CONFIRM_SIGHTINGS
                        || !target.equals(prior.target()) || !target.equals(currentObservation.pos())) {
                    // 目标消失（已被清理或彻底失去视野）→ 结束任务返程
                    journal.add("tick=" + in.tick() + " 清野：目标 " + prior.enemyId() + " 已不可见，解散编组");
                    continue;
                }
                if (manhattan(corePos, unit.position()) > RELEASE_RANGE) {
                    journal.add("tick=" + in.tick() + " 清野：单位 " + prior.unitId() + " 超出释放半径，撤回");
                    continue;
                }
                missions.put(prior.unitId(), new StrikeMission(prior.keyId(), prior.unitId(),
                        prior.enemyId(), target, prior.sinceTick()));
            }
        }

        // ② 新编组：按目标确认度与距离择优
        record Candidate(long dist, String enemyId, Cell target, String unitId, long keyId) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (AccountView account : in.accounts()) {
            if (!canJoinCampaign(account, threats)) {
                continue;
            }
            Cell corePos = corePos(account);
            if (corePos == null) {
                continue;
            }
            Map<String, GameObject> combatUnits = combatUnits(account);
            long alreadyOut = missions.values().stream().filter(m -> m.keyId() == account.keyId()).count();
            long spare = combatUnits.size() - MIN_HOME_GUARDS - alreadyOut;
            if (spare <= 0) {
                continue;
            }
            for (TargetObservation obs : observations.values()) {
                if (obs.sightings() < CONFIRM_SIGHTINGS) {
                    continue; // 未确认静止，不远征
                }
                Cell target = enemyById.get(obs.enemyId());
                if (target == null || manhattan(corePos, target) > STRIKE_RANGE) {
                    continue;
                }
                for (GameObject unit : combatUnits.values()) {
                    if (missions.containsKey(unit.id())) {
                        continue;
                    }
                    candidates.add(new Candidate(manhattan(unit.position(), target),
                            obs.enemyId(), target, unit.id(), account.keyId()));
                }
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::dist)
                .thenComparing(Candidate::enemyId)
                .thenComparing(Candidate::unitId));

        Map<String, Integer> strikersPerTarget = new HashMap<>();
        missions.values().forEach(m ->
                strikersPerTarget.merge(m.enemyId(), 1, Integer::sum));
        Map<Long, Long> outPerAccount = new HashMap<>();
        missions.values().forEach(m -> outPerAccount.merge(m.keyId(), 1L, Long::sum));

        Set<String> assignedUnits = new HashSet<>(missions.keySet());
        for (Candidate c : candidates) {
            if (assignedUnits.contains(c.unitId())) {
                continue;
            }
            if (strikersPerTarget.getOrDefault(c.enemyId(), 0) >= MAX_STRIKERS_PER_TARGET) {
                continue;
            }
            AccountView account = in.accounts().stream()
                    .filter(a -> a.keyId() == c.keyId()).findFirst().orElse(null);
            if (account == null) {
                continue;
            }
            long combat = combatUnits(account).size();
            if (combat - outPerAccount.getOrDefault(c.keyId(), 0L) <= MIN_HOME_GUARDS) {
                continue; // 留守底线
            }
            missions.put(c.unitId(), new StrikeMission(c.keyId(), c.unitId(), c.enemyId(), c.target(), in.tick()));
            assignedUnits.add(c.unitId());
            strikersPerTarget.merge(c.enemyId(), 1, Integer::sum);
            outPerAccount.merge(c.keyId(), 1L, Long::sum);
            journal.add("tick=" + in.tick() + " 清野：key=" + c.keyId() + " 单位=" + c.unitId()
                    + " → 目标 " + c.enemyId() + c.target());
        }
        return missions;
    }

    /** CLEAR 模式 + 自身不受压 才参与远征 */
    private static boolean canJoinCampaign(AccountView account, Map<Long, Assessment> threats) {
        if (account.config().mode() != PilotMode.CLEAR) {
            return false;
        }
        Assessment own = threats.get(account.keyId());
        return own == null || own.level().compareTo(ThreatLevel.ALERT) <= 0;
    }

    private static Map<String, GameObject> combatUnits(AccountView account) {
        Map<String, GameObject> units = new LinkedHashMap<>();
        if (account.state() == null || account.state().objects() == null) {
            return units;
        }
        for (GameObject o : account.state().objects()) {
            if (o.isUnit() && o.isControlled() && o.id() != null && o.position() != null) {
                UnitType type = UnitType.fromName(o.unitType());
                if (type == UnitType.VANGUARD || type == UnitType.RANGER) {
                    units.put(o.id(), o);
                }
            }
        }
        return units;
    }

    private static Cell corePos(AccountView account) {
        GameObject core = account.state() == null ? null : account.state().controlledCore();
        return core == null ? null : core.position();
    }

    private static long manhattan(Cell a, Cell b) {
        return com.arena.alliance.game.dto.GridMath.manhattan(a, b);
    }
}
