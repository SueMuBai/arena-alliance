package com.arena.alliance.hosting;

import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.hosting.plan.CommanderPlanner;
import com.arena.alliance.hosting.plan.PlanningModels;
import com.arena.alliance.hosting.plan.ScoutDoctrine;
import com.arena.alliance.rules.RuleService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 指挥调度器：Tick 对齐屏障 + 单线程指挥循环。
 * 各会话上报 state；同一 Tick 收齐全部 RUNNING 托管账号（或首报后超时 4s）即触发一次全联盟规划。
 * 迟到账号沿用其最近状态（planner 会校验时效），掉线账号自然退出编队。
 */
@Component
public class CommanderScheduler {

    private static final Logger log = LoggerFactory.getLogger(CommanderScheduler.class);
    private static final long ALIGN_TIMEOUT_MS = 4000;

    private final AllianceBlackboard blackboard;
    private final HostingService hostingService;
    private final WorldAggregator aggregator;
    private final RuleService ruleService;

    private final ExecutorService commander = Executors.newSingleThreadExecutor(
            r -> Thread.ofVirtual().name("alliance-commander").unstarted(r));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "commander-align-timer");
        t.setDaemon(true);
        return t;
    });

    private final Object alignLock = new Object();
    private long alignTick = -1;
    private final Set<Long> reported = ConcurrentHashMap.newKeySet();
    private volatile long lastPlannedTick = -1;

    public CommanderScheduler(AllianceBlackboard blackboard, HostingService hostingService,
                              WorldAggregator aggregator, RuleService ruleService) {
        this.blackboard = blackboard;
        this.hostingService = hostingService;
        this.aggregator = aggregator;
        this.ruleService = ruleService;
    }

    @PreDestroy
    public void shutdown() {
        commander.shutdown();
        timer.shutdown();
    }

    /** 会话线程上报（所有托管注册账号都进黑板；对齐屏障只统计 RUNNING） */
    public void reportState(long keyId, long userId, String gameUsername, long tick, PlayerState state) {
        blackboard.updateAccount(new AllianceBlackboard.AccountSnapshot(keyId, userId, gameUsername, tick, state));
        if (!hostingService.isRunning(keyId)) {
            return;
        }
        boolean complete = false;
        synchronized (alignLock) {
            if (tick > alignTick) {
                alignTick = tick;
                reported.clear();
                timer.schedule(() -> dispatchPlanning(tick), ALIGN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            if (tick == alignTick) {
                reported.add(keyId);
                complete = reported.containsAll(hostingService.alignmentKeyIds());
            }
        }
        if (complete) {
            dispatchPlanning(tick);
        }
    }

    public void removeAccount(long keyId) {
        blackboard.removeAccount(keyId);
        eligibilityChanged();
    }

    /** 连接中断/恢复时立即重算对齐屏障，不让断线账号等满 4 秒。 */
    public void eligibilityChanged() {
        long tick;
        boolean complete;
        synchronized (alignLock) {
            tick = alignTick;
            complete = tick >= 0 && reported.containsAll(hostingService.alignmentKeyIds());
        }
        if (complete) dispatchPlanning(tick);
    }

    private void dispatchPlanning(long tick) {
        try {
            commander.execute(() -> runPlanning(tick));
        } catch (RejectedExecutionException ignored) {
            // 关闭中
        }
    }

    private void runPlanning(long tick) {
        if (tick <= lastPlannedTick) {
            return; // 到齐与超时双触发去重
        }
        lastPlannedTick = tick;
        if (!ruleService.rules().hostingAllowed) {
            return;
        }
        List<PlanningModels.AccountView> accounts = new ArrayList<>();
        for (Long keyId : hostingService.runningKeyIds()) {
            if (hostingService.manualYielded(keyId, tick)) {
                continue;
            }
            AllianceBlackboard.AccountSnapshot snap = blackboard.account(keyId);
            if (snap == null || snap.tick() != tick) {
                continue; // 只能基于本 Tick 的权威 state 规划
            }
            accounts.add(new PlanningModels.AccountView(keyId, snap.userId(), snap.gameUsername(),
                    hostingService.configOf(keyId), snap.state()));
        }
        if (accounts.isEmpty()) {
            return;
        }
        try {
            List<PlanningModels.EnemyView> enemies = new ArrayList<>();
            for (WorldAggregator.EnemySighting e : aggregator.enemies().values()) {
                if (e.pos() != null) {
                    enemies.add(new PlanningModels.EnemyView(e.id(), e.kind(), e.unitType(), e.pos()));
                }
            }
            Set<Cell> resources = Set.copyOf(aggregator.resources().keySet());
            Set<Cell> obstacles = Set.copyOf(aggregator.obstacles().keySet());
            PlanningModels.PlanningInput input = new PlanningModels.PlanningInput(
                    tick,
                    accounts,
                    resources,
                    obstacles,
                    blackboard.leases(),
                    aggregator.memberIndex(),
                    enemies,
                    blackboard.enemyPositions(),
                    aggregator.coverageSnapshot(),
                    blackboard.scouts(),
                    blackboard.observations(),
                    blackboard.strikes());
            PlanningModels.PlanningResult result = CommanderPlanner.plan(input);
            blackboard.setLeases(result.leases());
            blackboard.setEnemyPositions(result.enemyPositions());
            blackboard.setScouts(result.scouts());
            blackboard.setObservations(result.observations());
            blackboard.setStrikes(result.strikes());
            result.journal().forEach(line -> log.info("[指挥] {}", line));
            result.plansByKey().forEach((keyId, plan) -> hostingService.submit(keyId, tick, plan));
            if (log.isDebugEnabled()) {
                log.debug("[指挥] tick={} 编队={} 计划={} 租约={} 侦察={} 清野={}",
                        tick, accounts.size(), result.plansByKey().size(),
                        result.leases().size(), result.scouts().size(), result.strikes().size());
            }
        } catch (Exception e) {
            log.error("[指挥] tick={} 规划失败", tick, e);
        }
    }
}
