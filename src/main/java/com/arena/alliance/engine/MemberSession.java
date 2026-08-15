package com.arena.alliance.engine;

import com.arena.alliance.game.GameCommandClient;
import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.GameWsClient;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlanReceipt;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.incident.IncidentType;
import com.arena.alliance.rules.RuleSettings;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.http.client.HttpClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个成员 apikey 的会话状态机。
 *
 * 消息全部投递到本会话的串行虚拟线程执行器，保证顺序处理：
 * - tick   → 记录当前 Tick，重置覆盖计数
 * - state  → 识别游戏用户名、更新全局聚合、提交伤亡证据
 * - received → 审查该玩家当前存储的作战计划；发现攻击联盟成员的动作时，
 *              用其本人 key 提交"净化计划"覆盖（AGENT 槽位后写覆盖先写）。
 *              MANUAL（网页手操）优先级更高无法覆盖，只能记录告警等待裁决。
 */
public class MemberSession implements GameWsClient.Listener {

    private static final Logger log = LoggerFactory.getLogger(MemberSession.class);

    /** 引擎回调面（由 AllianceEngine 实现），会话不直接依赖领域服务 */
    public interface Context {

        WorldAggregator aggregator();

        GameCommandClient commands();

        CasualtyJudge judge();

        RuleSettings rules();

        void recordIncident(IncidentType type, long tick, Long attackerUserId, String attackerName,
                            Long victimUserId, String victimName, String detail);

        /** @return false 表示该 key 与现有游戏账号重复，会话应停止 */
        boolean onGameUsernameLearned(long keyId, String gameUsername);

        void onKeyOnline(long keyId, long tick);

        void onKeyInvalid(long keyId, String error);

        /** 请求引擎停掉本会话（如重复 key） */
        void requestStop(long keyId);

        // ---- 联盟托管（指挥官）钩子 ----

        void hostingConnected(long keyId);

        void hostingDisconnected(long keyId);

        /** 每份完整 state 上报给指挥调度器（托管未开启时为空操作） */
        void hostingReportState(long keyId, long userId, String gameUsername, long tick, PlayerState state);

        /** 所有回执转交托管：外部 AGENT 计划 → 冲突暂停；MANUAL → 本 Tick 让路 */
        void hostingOnReceipt(long keyId, PlanReceipt receipt);

        /** 平台自己的提交（含反制覆盖）登记指纹，避免回显被误判为冲突 */
        void hostingRegisterPlatformPlan(long keyId, ObjectNode plan);

        /** 平台提交被明确拒绝时撤销待回显指纹 */
        void hostingUnregisterPlatformPlan(long keyId, ObjectNode plan);
    }

    private final long keyId;
    private final long userId;
    private final String token;
    private final Context ctx;
    private final GameWsClient ws;
    private final ExecutorService executor;

    private volatile String gameUsername;
    private volatile long currentTick;
    private volatile Map<String, Cell> unitPositions = Map.of();

    private int overridesThisTick;
    private long manualReportedTick = -1;
    private long detectedReportedTick = -1;
    private long limitReportedTick = -1;
    private final AtomicLong idemSeq = new AtomicLong();

    public MemberSession(long keyId, long userId, String knownGameUsername, String token,
                         HttpClient baseHttpClient, String wsUrl, Context ctx) {
        this.keyId = keyId;
        this.userId = userId;
        this.gameUsername = knownGameUsername;
        this.token = token;
        this.ctx = ctx;
        this.executor = Executors.newSingleThreadExecutor(
                r -> Thread.ofVirtual().name("member-session-" + keyId).unstarted(r));
        this.ws = new GameWsClient(baseHttpClient, wsUrl, token, "key-" + keyId, this);
    }

    public long keyId() {
        return keyId;
    }

    public long userId() {
        return userId;
    }

    public String gameUsername() {
        return gameUsername;
    }

    public long currentTick() {
        return currentTick;
    }

    public void start() {
        ws.start();
    }

    public void stop() {
        ws.stop();
        executor.shutdown();
    }

    private void dispatch(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // 会话已停止
        }
    }

    // ---- GameWsClient.Listener（Netty EventLoop 线程） ----

    @Override
    public void onOpen() {
        dispatch(() -> {
            ctx.hostingConnected(keyId);
            log.info("[key-{}] WS 已连接，等待新 state", keyId);
        });
    }

    @Override
    public void onMessage(String text) {
        dispatch(() -> handleMessage(text));
    }

    @Override
    public void onDisconnected(Integer closeCode, String detail, boolean fatal) {
        dispatch(() -> {
            ctx.hostingDisconnected(keyId);
            if (fatal) {
                log.warn("[key-{}] 凭证失效: code={} {}", keyId, closeCode, detail);
                ctx.onKeyInvalid(keyId, "close=" + closeCode + " " + detail);
            } else {
                log.info("[key-{}] WS 断开(code={})，将自动重连", keyId, closeCode);
            }
        });
    }

    // ---- 会话线程内 ----

    private void handleMessage(String text) {
        GameJson.ServerMessage message = GameJson.parseMessage(text);
        if (message.type() == null) {
            return;
        }
        switch (message.type()) {
            case "tick" -> {
                currentTick = message.data() == null ? currentTick : message.data().asLong(currentTick);
                overridesThisTick = 0;
            }
            case "state" -> handleState(GameJson.parseState(message.data()));
            case "received" -> handleReceipt(GameJson.parseReceipt(message.data()));
            default -> {
            }
        }
    }

    private void handleState(PlayerState state) {
        if (state == null) {
            return;
        }
        if (gameUsername == null) {
            GameObject core = state.controlledCore();
            if (core != null && core.ownerUsername() != null) {
                if (!ctx.onGameUsernameLearned(keyId, core.ownerUsername())) {
                    ctx.requestStop(keyId);
                    return;
                }
                gameUsername = core.ownerUsername();
                log.info("[key-{}] 识别游戏账号: {}", keyId, gameUsername);
            }
        }
        Map<String, Cell> positions = new HashMap<>();
        if (state.objects() != null) {
            for (GameObject o : state.objects()) {
                if (o.isControlled() && o.id() != null && o.position() != null) {
                    positions.put(o.id(), o.position());
                }
            }
        }
        unitPositions = Map.copyOf(positions);

        ctx.aggregator().updateMember(keyId, userId, displayName(), currentTick, state);
        if (state.events() != null && !state.events().isEmpty()) {
            ctx.judge().submit(userId, displayName(), state.events());
        }
        ctx.onKeyOnline(keyId, currentTick);
        ctx.hostingReportState(keyId, userId, gameUsername, currentTick, state);
    }

    private void handleReceipt(PlanReceipt receipt) {
        if (receipt == null || receipt.plan() == null) {
            return;
        }
        ctx.hostingOnReceipt(keyId, receipt);
        List<ThreatMonitor.Offense> offenses = ThreatMonitor.inspect(
                userId, receipt.plan(), unitPositions, ctx.aggregator().memberIndex());
        if (offenses.isEmpty()) {
            return;
        }
        RuleSettings rules = ctx.rules();
        ThreatMonitor.Offense first = offenses.get(0);
        String victimName = first.victim().gameUsername();
        Long victimUserId = first.victim().userId();
        String detail = summarize(offenses);

        if (receipt.isManual()) {
            if (manualReportedTick != receipt.tick()) {
                manualReportedTick = receipt.tick();
                ctx.recordIncident(IncidentType.INTERNAL_ATTACK_MANUAL, receipt.tick(),
                        userId, displayName(), victimUserId, victimName,
                        detail + "（手操指令优先级高于 AGENT，无法覆盖，等待伤亡裁决）");
            }
            return;
        }
        if (!rules.interventionEnabled) {
            if (detectedReportedTick != receipt.tick()) {
                detectedReportedTick = receipt.tick();
                ctx.recordIncident(IncidentType.INTERNAL_ATTACK_DETECTED, receipt.tick(),
                        userId, displayName(), victimUserId, victimName, detail + "（拦截已关闭）");
            }
            return;
        }
        if (overridesThisTick >= rules.maxOverridesPerTick) {
            if (limitReportedTick != receipt.tick()) {
                limitReportedTick = receipt.tick();
                ctx.recordIncident(IncidentType.OVERRIDE_LIMIT, receipt.tick(),
                        userId, displayName(), victimUserId, victimName,
                        "本 Tick 覆盖已达上限 " + rules.maxOverridesPerTick + " 次");
            }
            return;
        }
        overridesThisTick++;
        int attempt = overridesThisTick;

        ObjectNode sanitized = PlanSanitizer.sanitize(receipt.plan(),
                offenses.stream().map(ThreatMonitor.Offense::unitId).toList());
        String idem = "alc-" + keyId + "-" + receipt.tick() + "-" + idemSeq.incrementAndGet();
        ctx.hostingRegisterPlatformPlan(keyId, sanitized);

        ctx.commands().submitPlan(token, sanitized.toString(), idem).whenComplete((result, error) -> dispatch(() -> {
            if (error != null) {
                log.warn("[key-{}] 反制提交失败: {}", keyId, error.toString());
                return;
            }
            if (result.accepted()) {
                log.info("[key-{}] 已覆盖敌意计划 tick={} 第{}次: {}", keyId, receipt.tick(), attempt, detail);
                if (attempt == 1) {
                    ctx.recordIncident(IncidentType.INTERNAL_ATTACK_BLOCKED, receipt.tick(),
                            userId, displayName(), victimUserId, victimName, detail);
                }
            } else {
                ctx.hostingUnregisterPlatformPlan(keyId, sanitized);
                log.warn("[key-{}] 反制提交被拒 status={} body={}", keyId, result.status(), result.body());
            }
        }));
    }

    private String displayName() {
        return gameUsername != null ? gameUsername : ("key-" + keyId);
    }

    private static String summarize(List<ThreatMonitor.Offense> offenses) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ThreatMonitor.Offense o : offenses) {
            if (shown++ >= 4) {
                sb.append(" 等").append(offenses.size()).append("个动作");
                break;
            }
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append(o.actionType()).append("→").append(o.victim().gameUsername());
            if (o.targetCell() != null) {
                sb.append("@").append(o.targetCell());
            }
        }
        return sb.toString();
    }
}
