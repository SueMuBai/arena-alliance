package com.arena.alliance.hosting;

import com.arena.alliance.apikey.ApiKey;
import com.arena.alliance.game.GameCommandClient;
import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.dto.PlanReceipt;
import com.arena.alliance.incident.IncidentService;
import com.arena.alliance.incident.IncidentType;
import com.arena.alliance.rules.RuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 托管运行时服务。每个 Key 的自动计划与网页手操共用一条串行提交队列；
 * 只有 Arena Hero 返回 202 后才提交本地的已接受计划和手动覆盖。
 */
@Component
public class HostingService {

    private static final Logger log = LoggerFactory.getLogger(HostingService.class);
    private static final long FINGERPRINT_GRACE_MS = 60_000;

    private record FingerprintKey(long tick, String fingerprint) {
    }

    private static final class PendingFingerprint {
        int count;
        long expiresAt;

        PendingFingerprint(long expiresAt) {
            this.count = 1;
            this.expiresAt = expiresAt;
        }
    }

    private static final class ManualOverrides {
        final long tick;
        final Map<String, ObjectNode> unitActions = new HashMap<>();
        ObjectNode coreAction;

        ManualOverrides(long tick) {
            this.tick = tick;
        }

        ManualOverrides copy() {
            ManualOverrides copy = new ManualOverrides(tick);
            unitActions.forEach((id, action) -> copy.unitActions.put(id, action.deepCopy()));
            copy.coreAction = coreAction == null ? null : coreAction.deepCopy();
            return copy;
        }
    }

    private static final class Registration {
        final long userId;
        final String token;
        final Object stateLock = new Object();
        final Object queueLock = new Object();
        final Map<FingerprintKey, PendingFingerprint> fingerprints = new HashMap<>();
        volatile String gameUsername;
        volatile boolean enabled;
        volatile HostingConfig config;
        volatile boolean connected;
        volatile boolean everConnected;
        volatile boolean stateReady;
        volatile long stateTick = -1;
        ManualOverrides manual;
        ObjectNode lastAcceptedPlan;
        long lastAcceptedPlanTick = -1;
        CompletableFuture<Void> submitTail = CompletableFuture.completedFuture(null);

        Registration(long userId, String token, String gameUsername, boolean enabled, HostingConfig config) {
            this.userId = userId;
            this.token = token;
            this.gameUsername = gameUsername;
            this.enabled = enabled;
            this.config = config;
        }
    }

    private final Map<Long, Registration> registry = new ConcurrentHashMap<>();
    private final Set<Long> pausedConflict = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> manualYieldTick = new ConcurrentHashMap<>();
    private final AtomicLong submitSeq = new AtomicLong();

    private final RuleService ruleService;
    private final IncidentService incidents;
    private final GameCommandClient commandClient;

    public HostingService(RuleService ruleService, IncidentService incidents, GameCommandClient commandClient) {
        this.ruleService = ruleService;
        this.incidents = incidents;
        this.commandClient = commandClient;
    }

    public void register(ApiKey key, String token) {
        registry.put(key.getId(), new Registration(key.getUserId(), token, key.getGameUsername(),
                key.isHostingEnabled(), HostingConfig.parse(key.getHostingConfig())));
    }

    public void unregister(long keyId) {
        registry.remove(keyId);
        pausedConflict.remove(keyId);
        manualYieldTick.remove(keyId);
    }

    public void updateGameUsername(long keyId, String gameUsername) {
        Registration reg = registry.get(keyId);
        if (reg != null) reg.gameUsername = gameUsername;
    }

    public void applySettings(ApiKey key, boolean wasEnabled) {
        Registration reg = registry.get(key.getId());
        if (reg != null) {
            reg.enabled = key.isHostingEnabled();
            reg.config = HostingConfig.parse(key.getHostingConfig());
        }
        if (key.isHostingEnabled() && !wasEnabled) {
            incidents.record(IncidentType.HOSTING_STARTED, 0, null, null,
                    key.getUserId(), displayName(key.getId(), key.getGameUsername()),
                    "开启联盟托管，模式：" + HostingConfig.parse(key.getHostingConfig()).mode().label());
        } else if (!key.isHostingEnabled() && wasEnabled) {
            pausedConflict.remove(key.getId());
            incidents.record(IncidentType.HOSTING_STOPPED, 0, null, null,
                    key.getUserId(), displayName(key.getId(), key.getGameUsername()), "关闭联盟托管");
        }
    }

    public void resume(long keyId) {
        if (pausedConflict.remove(keyId)) {
            Registration reg = registry.get(keyId);
            incidents.record(IncidentType.HOSTING_RESUMED, 0, null, null,
                    reg == null ? null : reg.userId, displayName(keyId, reg == null ? null : reg.gameUsername),
                    "托管已恢复");
        }
    }

    /** WebSocket 已打开，但必须等到这次连接的新 state 才允许提交。 */
    public void onConnected(long keyId) {
        Registration reg = registry.get(keyId);
        if (reg == null) return;
        synchronized (reg.stateLock) {
            reg.connected = true;
            reg.everConnected = true;
            reg.stateReady = false;
        }
    }

    public void onDisconnected(long keyId) {
        Registration reg = registry.get(keyId);
        if (reg == null) return;
        synchronized (reg.stateLock) {
            reg.connected = false;
            reg.stateReady = false;
        }
    }

    /** 只有完整 state 才刷新可提交 Tick。 */
    public void onState(long keyId, long tick) {
        Registration reg = registry.get(keyId);
        if (reg == null) return;
        synchronized (reg.stateLock) {
            reg.connected = true;
            reg.everConnected = true;
            reg.stateReady = true;
            reg.stateTick = tick;
            if (reg.manual != null && reg.manual.tick != tick) reg.manual = null;
            purgeFingerprints(reg, tick);
        }
    }

    public void onReceipt(long keyId, PlanReceipt receipt) {
        Registration reg = registry.get(keyId);
        if (reg == null || !reg.enabled) return;
        if (receipt.isManual()) {
            manualYieldTick.put(keyId, receipt.tick());
            return;
        }
        if (!receipt.isAgent()) return;
        String fp = CanonicalJson.fingerprint(stripTick(receipt.plan()));
        if (consumeFingerprint(reg, new FingerprintKey(receipt.tick(), fp))) return;
        if (isRunning(keyId) && pausedConflict.add(keyId)) {
            incidents.record(IncidentType.HOSTING_PAUSED_CONFLICT, receipt.tick(), null, null,
                    reg.userId, displayName(keyId, reg.gameUsername),
                    "检测到该账号有其他 agent 在提交计划，托管已自动暂停；请关闭本地 agent 后在「我的 Key」恢复");
            log.warn("[key-{}] 托管冲突暂停：检测到外部 AGENT 计划", keyId);
        }
    }

    /** 供联盟伤害反制路径登记待回显计划。 */
    public void registerPlatformPlan(long keyId, JsonNode plan) {
        Registration reg = registry.get(keyId);
        if (reg != null) addFingerprint(reg, plan);
    }

    public void unregisterPlatformPlan(long keyId, JsonNode plan) {
        Registration reg = registry.get(keyId);
        if (reg != null) removeFingerprint(reg, fingerprintKey(plan));
    }

    /** 自动规划：不阻塞调度线程，队列执行时再复核 state Tick。 */
    public void submit(long keyId, long tick, ObjectNode plan) {
        Registration reg = registry.get(keyId);
        if (reg == null) return;
        enqueue(reg, () -> {
            if (!canSubmit(keyId, reg, tick)) return CompletableFuture.completedFuture(null);
            ObjectNode merged;
            synchronized (reg.stateLock) {
                merged = mergeManual(reg.manual, tick, plan);
                merged.put("tick", tick);
            }
            return dispatch(keyId, reg, tick, merged).thenAccept(result -> {
                if (result.accepted()) {
                    synchronized (reg.stateLock) {
                        reg.lastAcceptedPlan = merged.deepCopy();
                        reg.lastAcceptedPlanTick = tick;
                    }
                } else if (isTickMismatch(result)) {
                    log.debug("[key-{}] 计划过期被拒(tick={})，等待新 state 重新规划", keyId, tick);
                } else {
                    log.warn("[key-{}] 托管计划被拒 status={} body={}", keyId, result.status(), result.body());
                }
            });
        });
    }

    /**
     * 网页手操：串行构造候选覆盖，只有上游 202 后才提交到本地状态。
     */
    public CompletableFuture<GameCommandClient.CommandResult> submitManualAction(
            long keyId, long tick, String targetId, boolean isCore, ObjectNode action) {
        Registration reg = registry.get(keyId);
        if (reg == null) {
            return CompletableFuture.completedFuture(new GameCommandClient.CommandResult(404, "KEY_NOT_REGISTERED"));
        }
        return enqueue(reg, () -> {
            if (!canSubmit(keyId, reg, tick)) {
                return CompletableFuture.completedFuture(new GameCommandClient.CommandResult(409, "STALE_STATE"));
            }
            ManualOverrides candidate;
            ObjectNode base;
            synchronized (reg.stateLock) {
                candidate = reg.manual != null && reg.manual.tick == tick
                        ? reg.manual.copy() : new ManualOverrides(tick);
                if (isCore) candidate.coreAction = action.deepCopy();
                else candidate.unitActions.put(targetId, action.deepCopy());
                if (reg.lastAcceptedPlanTick == tick && reg.lastAcceptedPlan != null) {
                    base = reg.lastAcceptedPlan.deepCopy();
                } else {
                    base = GameJson.MAPPER.createObjectNode();
                    base.put("tick", tick);
                    base.set("unit_actions", GameJson.MAPPER.createObjectNode());
                }
            }
            ObjectNode merged = mergeManual(candidate, tick, base);
            merged.put("tick", tick);
            return dispatch(keyId, reg, tick, merged).thenApply(result -> {
                if (result.accepted()) {
                    synchronized (reg.stateLock) {
                        reg.manual = candidate;
                        reg.lastAcceptedPlan = merged.deepCopy();
                        reg.lastAcceptedPlanTick = tick;
                    }
                }
                return result;
            });
        });
    }

    private CompletableFuture<GameCommandClient.CommandResult> dispatch(
            long keyId, Registration reg, long tick, ObjectNode plan) {
        FingerprintKey fingerprint = addFingerprint(reg, plan);
        String idem = "host-" + keyId + "-" + tick + "-" + submitSeq.incrementAndGet();
        return commandClient.submitPlan(reg.token, plan.toString(), idem).handle((result, error) -> {
            if (error != null) {
                // 网络错误时服务端可能已接受，保留待回显指纹，不自动重试。
                log.warn("[key-{}] 计划提交结果未知: {}", keyId, error.toString());
                return new GameCommandClient.CommandResult(504, "SUBMIT_RESULT_UNKNOWN");
            }
            if (!result.accepted()) removeFingerprint(reg, fingerprint);
            return result;
        });
    }

    private boolean canSubmit(long keyId, Registration reg, long tick) {
        return isRunning(keyId) && reg.stateReady && reg.stateTick == tick;
    }

    private static ObjectNode mergeManual(ManualOverrides overrides, long tick, ObjectNode plan) {
        if (overrides == null || overrides.tick != tick
                || (overrides.unitActions.isEmpty() && overrides.coreAction == null)) return plan.deepCopy();
        ObjectNode merged = plan.deepCopy();
        JsonNode unitActions = merged.get("unit_actions");
        ObjectNode units = unitActions instanceof ObjectNode node ? node : GameJson.MAPPER.createObjectNode();
        overrides.unitActions.forEach((id, action) -> units.set(id, action.deepCopy()));
        merged.set("unit_actions", units);
        if (overrides.coreAction != null) merged.set("core_action", overrides.coreAction.deepCopy());
        return merged;
    }

    public Set<String> manuallyControlled(long keyId, long tick) {
        Registration reg = registry.get(keyId);
        if (reg == null) return Set.of();
        synchronized (reg.stateLock) {
            if (reg.manual == null || reg.manual.tick != tick) return Set.of();
            Set<String> ids = new HashSet<>(reg.manual.unitActions.keySet());
            if (reg.manual.coreAction != null) ids.add("CORE");
            return ids;
        }
    }

    public boolean isRunning(long keyId) {
        return statusOf(keyId) == HostingStatus.RUNNING;
    }

    public boolean manualYielded(long keyId, long tick) {
        Long yield = manualYieldTick.get(keyId);
        return yield != null && yield == tick;
    }

    public Set<Long> runningKeyIds() {
        Set<Long> running = new HashSet<>();
        for (Long keyId : registry.keySet()) if (isRunning(keyId)) running.add(keyId);
        return running;
    }

    /**
     * 当前应参与同 Tick 对齐屏障的账号。连接已经打开但还没收到本 Tick state 的账号
     * 也必须计入，否则首个上报 state 的账号会让屏障提前完成，后到的账号将被
     * {@link CommanderScheduler} 的单 Tick 去重漏掉。
     */
    public Set<Long> alignmentKeyIds() {
        if (!ruleService.rules().hostingAllowed) return Set.of();
        Set<Long> eligible = new HashSet<>();
        registry.forEach((keyId, reg) -> {
            if (reg.enabled && reg.connected && !pausedConflict.contains(keyId)) {
                eligible.add(keyId);
            }
        });
        return eligible;
    }

    public HostingStatus statusOf(long keyId) {
        Registration reg = registry.get(keyId);
        if (reg == null || !reg.enabled || !ruleService.rules().hostingAllowed) return HostingStatus.OFF;
        if (pausedConflict.contains(keyId)) return HostingStatus.PAUSED_CONFLICT;
        if (!reg.connected) return reg.everConnected ? HostingStatus.DISCONNECTED : HostingStatus.WAITING_STATE;
        return reg.stateReady ? HostingStatus.RUNNING : HostingStatus.WAITING_STATE;
    }

    public HostingConfig configOf(long keyId) {
        Registration reg = registry.get(keyId);
        return reg == null ? HostingConfig.DEFAULT : reg.config;
    }

    public long userIdOf(long keyId) {
        Registration reg = registry.get(keyId);
        return reg == null ? -1 : reg.userId;
    }

    public String gameUsernameOf(long keyId) {
        Registration reg = registry.get(keyId);
        return displayName(keyId, reg == null ? null : reg.gameUsername);
    }

    public long currentTick(long keyId, long fallback) {
        Registration reg = registry.get(keyId);
        return reg == null || reg.stateTick < 0 ? fallback : reg.stateTick;
    }

    private <T> CompletableFuture<T> enqueue(Registration reg, Supplier<CompletableFuture<T>> operation) {
        synchronized (reg.queueLock) {
            CompletableFuture<T> next = reg.submitTail.handle((ignored, error) -> null)
                    .thenCompose(ignored -> operation.get());
            reg.submitTail = next.handle((ignored, error) -> null);
            return next;
        }
    }

    private static FingerprintKey addFingerprint(Registration reg, JsonNode plan) {
        FingerprintKey key = fingerprintKey(plan);
        synchronized (reg.fingerprints) {
            PendingFingerprint pending = reg.fingerprints.get(key);
            if (pending == null) reg.fingerprints.put(key,
                    new PendingFingerprint(System.currentTimeMillis() + FINGERPRINT_GRACE_MS));
            else {
                pending.count++;
                pending.expiresAt = System.currentTimeMillis() + FINGERPRINT_GRACE_MS;
            }
        }
        return key;
    }

    private static boolean consumeFingerprint(Registration reg, FingerprintKey key) {
        synchronized (reg.fingerprints) {
            PendingFingerprint pending = reg.fingerprints.get(key);
            if (pending == null || pending.count <= 0) return false;
            if (pending.expiresAt < System.currentTimeMillis()) {
                reg.fingerprints.remove(key);
                return false;
            }
            if (--pending.count == 0) reg.fingerprints.remove(key);
            return true;
        }
    }

    private static void removeFingerprint(Registration reg, FingerprintKey key) {
        consumeFingerprint(reg, key);
    }

    private static void purgeFingerprints(Registration reg, long currentTick) {
        long now = System.currentTimeMillis();
        synchronized (reg.fingerprints) {
            reg.fingerprints.entrySet().removeIf(e -> e.getValue().expiresAt < now
                    || (e.getKey().tick() < currentTick - 4));
        }
    }

    private static FingerprintKey fingerprintKey(JsonNode plan) {
        long tick = plan == null ? -1 : plan.path("tick").asLong(-1);
        return new FingerprintKey(tick, CanonicalJson.fingerprint(stripTick(plan)));
    }

    private static boolean isTickMismatch(GameCommandClient.CommandResult result) {
        return result.status() == 409 && result.body() != null && result.body().contains("TICK_MISMATCH");
    }

    private static JsonNode stripTick(JsonNode plan) {
        if (plan instanceof ObjectNode obj) {
            ObjectNode copy = obj.deepCopy();
            copy.remove("tick");
            return copy;
        }
        return plan;
    }

    private static String displayName(long keyId, String gameUsername) {
        return gameUsername != null ? gameUsername : ("key-" + keyId);
    }
}
