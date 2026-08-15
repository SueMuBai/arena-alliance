package com.arena.alliance.hosting;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.hosting.plan.PlanningModels;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联盟指挥黑板：托管账号最新状态快照 + 跨 Tick 任务台账（M1：资源租约）。
 * 运行态不落库，重启后由下一个 Tick 重建。
 */
@Component
public class AllianceBlackboard {

    public record AccountSnapshot(long keyId, long userId, String gameUsername,
                                  long tick, PlayerState state) {
    }

    private final Map<Long, AccountSnapshot> accounts = new ConcurrentHashMap<>();
    private volatile Map<Cell, PlanningModels.Lease> leases = Map.of();
    /** 上一轮规划的敌人位置：威胁评估据此判定"移动/逼近"（静止远敌不动员） */
    private volatile Map<String, Cell> enemyPositions = Map.of();
    /** 侦察任务台账：unitId → 认领的扇区（跨 Tick 续约，避免每 Tick 换目标） */
    private volatile Map<String, PlanningModels.ScoutMission> scouts = Map.of();
    /** 静止目标观测台账：enemyId → 连续观测记录（清野确认依据） */
    private volatile Map<String, PlanningModels.TargetObservation> observations = Map.of();
    /** 清野打击台账：unitId → 集火任务（保持编组，避免半路换目标） */
    private volatile Map<String, PlanningModels.StrikeMission> strikes = Map.of();

    public void updateAccount(AccountSnapshot snapshot) {
        accounts.put(snapshot.keyId(), snapshot);
    }

    public void removeAccount(long keyId) {
        accounts.remove(keyId);
    }

    public AccountSnapshot account(long keyId) {
        return accounts.get(keyId);
    }

    public Map<Long, AccountSnapshot> accounts() {
        return accounts;
    }

    public Map<Cell, PlanningModels.Lease> leases() {
        return leases;
    }

    public void setLeases(Map<Cell, PlanningModels.Lease> leases) {
        this.leases = Map.copyOf(leases);
    }

    public Map<String, Cell> enemyPositions() {
        return enemyPositions;
    }

    public void setEnemyPositions(Map<String, Cell> enemyPositions) {
        this.enemyPositions = Map.copyOf(enemyPositions);
    }

    public Map<String, PlanningModels.ScoutMission> scouts() {
        return scouts;
    }

    public void setScouts(Map<String, PlanningModels.ScoutMission> scouts) {
        this.scouts = Map.copyOf(scouts);
    }

    public Map<String, PlanningModels.TargetObservation> observations() {
        return observations;
    }

    public void setObservations(Map<String, PlanningModels.TargetObservation> observations) {
        this.observations = Map.copyOf(observations);
    }

    public Map<String, PlanningModels.StrikeMission> strikes() {
        return strikes;
    }

    public void setStrikes(Map<String, PlanningModels.StrikeMission> strikes) {
        this.strikes = Map.copyOf(strikes);
    }
}
