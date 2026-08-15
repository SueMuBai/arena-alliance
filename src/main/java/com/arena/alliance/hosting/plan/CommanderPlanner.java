package com.arena.alliance.hosting.plan;

import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.game.dto.UnitType;
import com.arena.alliance.hosting.PilotMode;
import com.arena.alliance.hosting.plan.PlanningModels.AccountView;
import com.arena.alliance.hosting.plan.PlanningModels.EnemyView;
import com.arena.alliance.hosting.plan.PlanningModels.Lease;
import com.arena.alliance.hosting.plan.PlanningModels.PlanningInput;
import com.arena.alliance.hosting.plan.PlanningModels.PlanningResult;
import com.arena.alliance.hosting.plan.PlanningModels.ScoutMission;
import com.arena.alliance.hosting.plan.PlanningModels.StrikeMission;
import com.arena.alliance.hosting.plan.PlanningModels.TargetObservation;
import com.arena.alliance.hosting.plan.ThreatAssessor.Assessment;
import com.arena.alliance.hosting.plan.ThreatAssessor.ThreatLevel;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

/**
 * 联盟指挥规划器（M1 经济 + M2 防御驰援 + M3 侦察 + M4 清野）——纯函数，同输入同输出，可录快照回放。
 *
 * 管线：威胁评估（每账号）→ 驰援分派（保护含非托管成员在内的全体盟友）
 *      → 静止目标观测 → 清野编组（⚔CLEAR 模式跨账号集火）
 *      → 租约延续 → 全联盟 worker↔资源全局贪心分配（跨账号不互抢）
 *      → 侦察扇区分派（剩余空闲 worker，跨账号不重叠）
 *      → 各账号编译（治疗 > 撤离/反击/驰援/清野/守位 > 采集/回仓/侦察 > 生产/预撤）→ 盟友安全闸。
 *
 * 扩展点：新玩法以"目标产出 → 分派 → 技能编译"的形式插入本管线，骨架与黑板不变。
 */
public final class CommanderPlanner {

    /** 平时战斗单位守家半径 */
    static final int GUARD_RADIUS = 3;
    /** 资源分配的最大曼哈顿距离 */
    static final int MAX_ASSIGN_DISTANCE = 40;
    /** 驰援判定：敌战斗单位距任一盟友对象 ≤ 该值即产生驰援点 */
    static final int ASSIST_TRIGGER_RADIUS = 12;
    /** 驰援出动距离（按模式）：CLEAR 更远，TURTLE 不出动 */
    static final int ASSIST_RANGE_DEFAULT = 24;
    static final int ASSIST_RANGE_CLEAR = 32;

    private CommanderPlanner() {
    }

    public static PlanningResult plan(PlanningInput in) {
        List<String> journal = new ArrayList<>();
        Map<Long, ObjectNode> plans = new LinkedHashMap<>();

        // --- 威胁评估（每账号）---
        Map<Long, Assessment> threats = new LinkedHashMap<>();
        for (AccountView account : in.accounts()) {
            GameObject core = account.state() == null ? null : account.state().controlledCore();
            Assessment assessment = ThreatAssessor.assess(core == null ? null : core.position(),
                    account.config().mode(), in.enemies(), in.priorEnemyPositions(), in.obstacles());
            threats.put(account.keyId(), assessment);
            if (assessment.level() != ThreatLevel.NORMAL) {
                journal.add("tick=" + in.tick() + " key=" + account.keyId()
                        + " threat=" + assessment.level() + " nearest=" + assessment.nearestThreat());
            }
        }

        // --- 驰援分派 ---
        Map<Long, List<Cell>> assists = allocateAssists(in, threats, journal);

        // --- 清野：静止目标观测 + 跨账号集火编组 ---
        Map<String, TargetObservation> observations = ClearDoctrine.observe(in);
        Map<String, StrikeMission> strikes = ClearDoctrine.allocate(in, observations, threats, journal);

        // --- 经济：租约延续 + 全局贪心分配 ---
        record WorkerRef(long keyId, String unitId, Cell pos) {
        }
        Map<String, WorkerRef> emptyWorkers = new LinkedHashMap<>();
        for (AccountView account : in.accounts()) {
            PlayerState state = account.state();
            if (state == null || state.objects() == null) {
                continue;
            }
            for (GameObject o : state.objects()) {
                if (o.isUnit() && o.isControlled() && o.id() != null && o.position() != null
                        && UnitType.WORKER == UnitType.fromName(o.unitType())
                        && (o.cargo() == null || o.cargo() == 0)) {
                    emptyWorkers.put(o.id(), new WorkerRef(account.keyId(), o.id(), o.position()));
                }
            }
        }
        Map<Cell, Lease> leases = new LinkedHashMap<>();
        Set<String> assignedWorkers = new HashSet<>();
        for (Map.Entry<Cell, Lease> e : in.priorLeases().entrySet()) {
            Lease lease = e.getValue();
            WorkerRef worker = emptyWorkers.get(lease.unitId());
            if (in.resources().contains(e.getKey()) && worker != null && worker.keyId() == lease.keyId()) {
                leases.put(e.getKey(), lease);
                assignedWorkers.add(lease.unitId());
            }
        }
        record Candidate(long dist, Cell cell, WorkerRef worker) {
        }
        record ResourceChunk(long x, long y) {
        }
        Map<ResourceChunk, List<Cell>> resourceIndex = new HashMap<>();
        for (Cell resource : in.resources()) {
            ResourceChunk chunk = new ResourceChunk(Math.floorDiv(resource.x(), 32), Math.floorDiv(resource.y(), 32));
            resourceIndex.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(resource);
        }
        resourceIndex.values().forEach(cells -> cells.sort(Comparator.comparingLong(Cell::x).thenComparingLong(Cell::y)));

        Set<Cell> takenCells = new HashSet<>(leases.keySet());
        Comparator<Candidate> candidateOrder = Comparator.comparingLong(Candidate::dist)
                .thenComparingLong(c -> c.cell().x())
                .thenComparingLong(c -> c.cell().y())
                .thenComparing(c -> c.worker().unitId());
        Function<WorkerRef, Candidate> nearestAvailable = worker -> {
            Candidate best = null;
            long baseX = Math.floorDiv(worker.pos().x(), 32);
            long baseY = Math.floorDiv(worker.pos().y(), 32);
            for (long cx = baseX - 2; cx <= baseX + 2; cx++) {
                for (long cy = baseY - 2; cy <= baseY + 2; cy++) {
                    for (Cell cell : resourceIndex.getOrDefault(new ResourceChunk(cx, cy), List.of())) {
                        if (takenCells.contains(cell)) continue;
                        long dist = manhattan(worker.pos(), cell);
                        if (dist > MAX_ASSIGN_DISTANCE) continue;
                        Candidate candidate = new Candidate(dist, cell, worker);
                        if (best == null || candidateOrder.compare(candidate, best) < 0) best = candidate;
                    }
                }
            }
            return best;
        };
        PriorityQueue<Candidate> candidates = new PriorityQueue<>(candidateOrder);
        for (WorkerRef worker : emptyWorkers.values()) {
            if (!assignedWorkers.contains(worker.unitId())) {
                Candidate candidate = nearestAvailable.apply(worker);
                if (candidate != null) candidates.add(candidate);
            }
        }
        while (!candidates.isEmpty()) {
            Candidate c = candidates.poll();
            if (assignedWorkers.contains(c.worker().unitId())) continue;
            if (takenCells.contains(c.cell())) {
                Candidate next = nearestAvailable.apply(c.worker());
                if (next != null) candidates.add(next);
                continue;
            }
            leases.put(c.cell(), new Lease(c.worker().keyId(), c.worker().unitId(), in.tick()));
            assignedWorkers.add(c.worker().unitId());
            takenCells.add(c.cell());
        }
        // 队列中始终每个 Worker 最多一条候选，不再物化 Worker×Resource 边集。
        Map<String, Cell> targetByWorker = new HashMap<>();
        leases.forEach((cell, lease) -> targetByWorker.put(lease.unitId(), cell));

        // --- 侦察：把没接到采集任务的空载 Worker 派去探明未知扇区 ---
        Map<Long, Map<String, Cell>> idleWorkers = new LinkedHashMap<>();
        for (WorkerRef worker : emptyWorkers.values()) {
            if (!assignedWorkers.contains(worker.unitId())) {
                idleWorkers.computeIfAbsent(worker.keyId(), k -> new LinkedHashMap<>())
                        .put(worker.unitId(), worker.pos());
            }
        }
        Map<String, ScoutMission> scouts = ScoutDoctrine.allocate(in, idleWorkers, journal);

        // --- 各账号编译 ---
        for (AccountView account : in.accounts()) {
            ObjectNode plan = compileAccount(account, in, threats.get(account.keyId()),
                    assists.getOrDefault(account.keyId(), List.of()), targetByWorker, scouts, strikes, journal);
            if (plan != null) {
                plans.put(account.keyId(), plan);
            }
        }

        Map<String, Cell> enemyPositions = new LinkedHashMap<>();
        for (EnemyView enemy : in.enemies()) {
            enemyPositions.put(enemy.id(), enemy.pos());
        }
        return new PlanningResult(plans, leases, enemyPositions, scouts, observations, strikes, journal);
    }

    /**
     * 驰援：敌战斗单位威胁到任一盟友对象（含非托管成员）时，
     * 派离威胁点最近、模式允许、自身安全（≤ALERT）的托管账号前往。
     * 威胁点在该账号自己的防区内则由自卫逻辑处理，不重复派单。
     */
    private static Map<Long, List<Cell>> allocateAssists(PlanningInput in,
                                                         Map<Long, Assessment> threats,
                                                         List<String> journal) {
        Map<Long, List<Cell>> assists = new LinkedHashMap<>();
        for (EnemyView enemy : in.enemies()) {
            if (ThreatAssessor.attackRange(enemy) == 0) {
                continue;
            }
            boolean threatensAlly = in.allyIndex().byCell().keySet().stream()
                    .anyMatch(cell -> manhattan(cell, enemy.pos()) <= ASSIST_TRIGGER_RADIUS);
            if (!threatensAlly) {
                continue;
            }
            AccountView best = null;
            long bestDist = Long.MAX_VALUE;
            for (AccountView account : in.accounts()) {
                if (account.config().mode() == PilotMode.TURTLE) {
                    continue;
                }
                Assessment own = threats.get(account.keyId());
                if (own != null && own.level().compareTo(ThreatLevel.ALERT) > 0) {
                    continue; // 自身正被压迫，先自卫
                }
                GameObject core = account.state() == null ? null : account.state().controlledCore();
                if (core == null || core.position() == null) {
                    continue;
                }
                long dist = manhattan(core.position(), enemy.pos());
                int range = account.config().mode() == PilotMode.CLEAR ? ASSIST_RANGE_CLEAR : ASSIST_RANGE_DEFAULT;
                if (dist <= ASSIST_TRIGGER_RADIUS || dist > range) {
                    continue; // 防区内自卫已覆盖 / 太远不出动
                }
                if (dist < bestDist) {
                    bestDist = dist;
                    best = account;
                }
            }
            if (best != null) {
                assists.computeIfAbsent(best.keyId(), k -> new ArrayList<>()).add(enemy.pos());
                journal.add("tick=" + in.tick() + " 驰援：key=" + best.keyId() + " → 威胁点 " + enemy.pos());
            }
        }
        return assists;
    }

    private static ObjectNode compileAccount(AccountView account, PlanningInput in,
                                             Assessment threat, List<Cell> assistTargets,
                                             Map<String, Cell> targetByWorker,
                                             Map<String, ScoutMission> scouts,
                                             Map<String, StrikeMission> strikes, List<String> journal) {
        PlayerState state = account.state();
        if (state == null || state.objects() == null || !"ACTIVE".equals(state.status())) {
            return null;
        }
        GameObject core = state.controlledCore();
        Cell corePos = core == null ? null : core.position();
        boolean coreNormal = core != null && "NORMAL".equals(core.state());

        Map<String, Cell> positions = new HashMap<>();
        Set<Cell> occupied = new HashSet<>();
        List<GameObject> units = new ArrayList<>();
        for (GameObject o : state.objects()) {
            if ((o.isUnit() || o.isCore()) && o.isControlled() && o.id() != null && o.position() != null) {
                positions.put(o.id(), o.position());
                if (o.isUnit()) {
                    occupied.add(o.position());
                    units.add(o);
                }
            }
        }

        ObjectNode actions = GameJson.MAPPER.createObjectNode();
        Set<Cell> claimed = new HashSet<>();
        long healBudget = state.resources();
        long plannedHealSpend = 0;

        for (GameObject o : units) {
            UnitType type = UnitType.fromName(o.unitType());
            if (type == null) {
                continue;
            }
            Cell pos = o.position();

            // ① 治疗优先：受伤 + 站在自家静止 Core 上 + 有预算
            int missingHp = o.hp() == null ? 0 : Math.max(0, type.maxHp() - o.hp());
            if (missingHp > 0 && coreNormal && pos.equals(corePos)
                    && healBudget - plannedHealSpend >= missingHp + ProductionPlanner.EMERGENCY_RESERVE) {
                putAction(actions, o.id(), "HEAL");
                plannedHealSpend += missingHp;
                continue;
            }

            if (type == UnitType.WORKER) {
                // ② 撤离：敌战斗单位压境的 Worker 放下手头任务回 Core
                if (corePos != null && !pos.equals(corePos)
                        && DefensePlanner.underCombatPressure(pos, in.enemies())) {
                    moveTowards(actions, o.id(), pos, corePos, in.obstacles(), occupied, claimed);
                    continue;
                }
                int cargo = o.cargo() == null ? 0 : o.cargo();
                if (cargo > 0) {
                    if (corePos == null) {
                        continue;
                    }
                    if (pos.equals(corePos)) {
                        if (coreNormal) {
                            putAction(actions, o.id(), "DEPOSIT");
                        }
                    } else {
                        moveTowards(actions, o.id(), pos, corePos, in.obstacles(), occupied, claimed);
                    }
                } else {
                    Cell target = targetByWorker.get(o.id());
                    if (target != null) {
                        if (pos.equals(target)) {
                            putAction(actions, o.id(), "HARVEST");
                        } else {
                            moveTowards(actions, o.id(), pos, target, in.obstacles(), occupied, claimed);
                        }
                        continue;
                    }
                    // 侦察：朝认领扇区中心推进（遇敌已在上面的撤离分支处理）
                    ScoutMission mission = scouts.get(o.id());
                    if (mission != null) {
                        Cell frontier = ScoutDoctrine.frontierTarget(
                                mission, pos, in.exploredCoverage(), in.obstacles());
                        if (frontier != null) {
                            moveTowards(actions, o.id(), pos, frontier, in.obstacles(), occupied, claimed);
                        }
                    }
                }
            } else {
                // ③ 战斗单位：合法立即攻击 > 驰援 > 威胁守位 > 平时守家
                ObjectNode attack = DefensePlanner.tryAttack(pos, type, in.enemies(), in.obstacles());
                if (attack != null) {
                    actions.set(o.id(), attack);
                    continue;
                }
                if (!assistTargets.isEmpty()) {
                    Cell target = nearest(pos, assistTargets);
                    if (manhattan(pos, target) > 2) {
                        moveTowards(actions, o.id(), pos, target, in.obstacles(), occupied, claimed);
                    }
                    continue;
                }
                // 清野：向确认的静止目标推进（进入射程后由上面的合法攻击分支开火）
                StrikeMission strike = strikes.get(o.id());
                if (strike != null) {
                    moveTowards(actions, o.id(), pos, strike.target(), in.obstacles(), occupied, claimed);
                    continue;
                }
                if (threat.level().compareTo(ThreatLevel.ALERT) >= 0
                        && threat.nearestThreat() != null && corePos != null) {
                    Cell post = DefensePlanner.guardPost(corePos, threat.nearestThreat(), type);
                    if (!pos.equals(post)) {
                        moveTowards(actions, o.id(), pos, post, in.obstacles(), occupied, claimed);
                    }
                    continue;
                }
                if (corePos != null && manhattan(pos, corePos) > GUARD_RADIUS) {
                    moveTowards(actions, o.id(), pos, corePos, in.obstacles(), occupied, claimed);
                }
            }
        }

        // ④ Core：预撤 > 生产/治疗/修盾
        ObjectNode coreAction = null;
        if (coreNormal && threat.level().compareTo(ThreatLevel.PRE_EVADE) >= 0) {
            Set<Cell> coreBlocked = new HashSet<>(occupied);
            for (EnemyView enemy : in.enemies()) {
                coreBlocked.add(enemy.pos());
            }
            String direction = DefensePlanner.evadeDirection(corePos, in.enemies(),
                    in.obstacles(), in.resources(), coreBlocked);
            if (direction != null) {
                coreAction = GameJson.MAPPER.createObjectNode();
                coreAction.put("type", "START_MOVE");
                coreAction.put("direction", direction);
                journal.add("tick=" + in.tick() + " key=" + account.keyId() + " Core 预撤 " + direction);
            }
        }
        if (coreAction == null) {
            coreAction = ProductionPlanner.coreAction(state, account.config(), plannedHealSpend);
        }

        if (actions.isEmpty() && coreAction == null) {
            return null;
        }
        ObjectNode plan = GameJson.MAPPER.createObjectNode();
        plan.put("tick", in.tick());
        plan.set("unit_actions", actions);
        if (coreAction != null) {
            plan.set("core_action", coreAction);
        }
        ObjectNode safe = AllySafetyGate.enforce(account.userId(), plan, positions, in.allyIndex());
        if (safe != plan) {
            journal.add("tick=" + in.tick() + " key=" + account.keyId() + " 安全闸剔除了指向盟友的动作");
        }
        return safe;
    }

    private static Cell nearest(Cell from, List<Cell> targets) {
        Cell best = targets.get(0);
        long bestDist = manhattan(from, best);
        for (Cell target : targets) {
            long dist = manhattan(from, target);
            if (dist < bestDist) {
                bestDist = dist;
                best = target;
            }
        }
        return best;
    }

    private static void moveTowards(ObjectNode actions, String unitId, Cell from, Cell to,
                                    Set<Cell> obstacles, Set<Cell> occupied, Set<Cell> claimed) {
        Set<Cell> blocked = new HashSet<>(occupied);
        blocked.addAll(claimed);
        blocked.remove(from);
        String direction = Pathfinder.nextStep(from, to, obstacles, blocked);
        if (direction == null) {
            return;
        }
        ObjectNode move = GameJson.MAPPER.createObjectNode();
        move.put("type", "MOVE");
        move.put("direction", direction);
        actions.set(unitId, move);
        claimed.add(from.move(direction));
    }

    private static void putAction(ObjectNode actions, String unitId, String type) {
        ObjectNode node = GameJson.MAPPER.createObjectNode();
        node.put("type", type);
        actions.set(unitId, node);
    }

    private static long manhattan(Cell a, Cell b) {
        return com.arena.alliance.game.dto.GridMath.manhattan(a, b);
    }
}
