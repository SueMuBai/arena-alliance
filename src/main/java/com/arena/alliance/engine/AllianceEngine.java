package com.arena.alliance.engine;

import com.arena.alliance.apikey.ApiKey;
import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.config.AllianceProperties;
import com.arena.alliance.game.GameCommandClient;
import com.arena.alliance.game.dto.PlanReceipt;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.hosting.CommanderScheduler;
import com.arena.alliance.hosting.HostingService;
import com.arena.alliance.incident.IncidentService;
import com.arena.alliance.incident.IncidentType;
import com.arena.alliance.rules.RuleSettings;
import com.arena.alliance.rules.RuleService;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联盟引擎：管理全部成员会话的生命周期。
 * key 的增删/启停、成员被踢都通过应用事件解耦触达这里。
 */
@Component
public class AllianceEngine implements MemberSession.Context {

    private static final Logger log = LoggerFactory.getLogger(AllianceEngine.class);

    public record OnlineMark(long tick, Instant at) {
    }

    private final AllianceProperties props;
    private final HttpClient baseHttpClient;
    private final WorldAggregator aggregator;
    private final GameCommandClient commandClient;
    private final CasualtyJudge judge;
    private final IncidentService incidents;
    private final RuleService ruleService;
    private final ApiKeyService apiKeyService;
    private final UserService userService;
    private final HostingService hostingService;
    private final CommanderScheduler commanderScheduler;

    private final Map<Long, MemberSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, OnlineMark> online = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastSeenPersisted = new ConcurrentHashMap<>();

    public AllianceEngine(AllianceProperties props, HttpClient baseHttpClient, WorldAggregator aggregator,
                          GameCommandClient commandClient, CasualtyJudge judge, IncidentService incidents,
                          RuleService ruleService, ApiKeyService apiKeyService, UserService userService,
                          HostingService hostingService, CommanderScheduler commanderScheduler) {
        this.props = props;
        this.baseHttpClient = baseHttpClient;
        this.aggregator = aggregator;
        this.commandClient = commandClient;
        this.judge = judge;
        this.incidents = incidents;
        this.ruleService = ruleService;
        this.apiKeyService = apiKeyService;
        this.userService = userService;
        this.hostingService = hostingService;
        this.commanderScheduler = commanderScheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void startAll() {
        int started = 0;
        for (ApiKey key : apiKeyService.enabledKeys()) {
            try {
                User owner = userService.get(key.getUserId());
                if (owner.getStatus() == User.Status.ACTIVE) {
                    startSession(key);
                    started++;
                }
            } catch (Exception e) {
                log.warn("启动 key {} 会话失败: {}", key.getId(), e.getMessage());
            }
        }
        log.info("联盟引擎已启动 {} 个成员会话，游戏服务器: {}", started, props.game().apiBase());
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(MemberSession::stop);
        sessions.clear();
    }

    @EventListener
    public void onKeyActivated(EngineEvents.KeyActivated event) {
        try {
            ApiKey key = apiKeyService.find(event.keyId());
            if (key.getStatus() == ApiKey.Status.ENABLED) {
                User owner = userService.get(key.getUserId());
                if (owner.getStatus() == User.Status.ACTIVE) {
                    startSession(key);
                }
            }
        } catch (Exception e) {
            log.warn("激活 key {} 失败: {}", event.keyId(), e.getMessage());
        }
    }

    @EventListener
    public void onKeyDeactivated(EngineEvents.KeyDeactivated event) {
        stopSession(event.keyId());
    }

    @EventListener
    public void onMemberKicked(EngineEvents.MemberKicked event) {
        apiKeyService.disableAllOf(event.userId());
        List<MemberSession> owned = sessions.values().stream()
                .filter(s -> s.userId() == event.userId())
                .toList();
        owned.forEach(s -> stopSession(s.keyId()));
        log.info("已停止被踢成员 {} 的 {} 个会话", event.userId(), owned.size());
    }

    public synchronized void startSession(ApiKey key) {
        if (sessions.containsKey(key.getId())) {
            return;
        }
        String token = apiKeyService.decrypt(key);
        MemberSession session = new MemberSession(key.getId(), key.getUserId(), key.getGameUsername(),
                token, baseHttpClient, props.game().wsUrl(), this);
        sessions.put(key.getId(), session);
        hostingService.register(key, token);
        session.start();
        log.info("会话已启动: key={} user={}", key.getId(), key.getUserId());
    }

    public synchronized void stopSession(long keyId) {
        MemberSession session = sessions.remove(keyId);
        if (session != null) {
            session.stop();
            aggregator.removeMember(keyId);
            online.remove(keyId);
            hostingService.unregister(keyId);
            commanderScheduler.removeAccount(keyId);
        }
    }

    public boolean isKeyOnline(long keyId) {
        OnlineMark mark = online.get(keyId);
        return mark != null && Duration.between(mark.at(), Instant.now()).getSeconds() < 60;
    }

    public Map<Long, OnlineMark> onlineMarks() {
        return online;
    }

    public int sessionCount() {
        return sessions.size();
    }

    // ---- MemberSession.Context ----

    @Override
    public WorldAggregator aggregator() {
        return aggregator;
    }

    @Override
    public GameCommandClient commands() {
        return commandClient;
    }

    @Override
    public CasualtyJudge judge() {
        return judge;
    }

    @Override
    public RuleSettings rules() {
        return ruleService.rules();
    }

    @Override
    public void recordIncident(IncidentType type, long tick, Long attackerUserId, String attackerName,
                               Long victimUserId, String victimName, String detail) {
        incidents.record(type, tick, attackerUserId, attackerName, victimUserId, victimName, detail);
    }

    @Override
    public boolean onGameUsernameLearned(long keyId, String gameUsername) {
        boolean ok = apiKeyService.updateGameUsername(keyId, gameUsername);
        if (!ok) {
            incidents.record(IncidentType.KEY_DUPLICATE, aggregator.maxTick(), null, null, null, null,
                    "key-" + keyId + " 与已启用 key 同属游戏账号 " + gameUsername + "，已停用");
        } else {
            hostingService.updateGameUsername(keyId, gameUsername);
        }
        return ok;
    }

    @Override
    public void onKeyOnline(long keyId, long tick) {
        online.put(keyId, new OnlineMark(tick, Instant.now()));
        Long persisted = lastSeenPersisted.get(keyId);
        if (persisted == null || tick - persisted >= 4) {
            lastSeenPersisted.put(keyId, tick);
            try {
                apiKeyService.touchLastSeen(keyId, tick);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onKeyInvalid(long keyId, String error) {
        MemberSession session = sessions.get(keyId);
        Long ownerId = session == null ? null : session.userId();
        apiKeyService.markInvalid(keyId, error);
        incidents.record(IncidentType.KEY_INVALID, aggregator.maxTick(), null, null, ownerId, null,
                "key-" + keyId + " 凭证失效：" + error);
        stopSession(keyId);
    }

    @Override
    public void requestStop(long keyId) {
        stopSession(keyId);
    }

    @Override
    public void hostingConnected(long keyId) {
        hostingService.onConnected(keyId);
        commanderScheduler.eligibilityChanged();
    }

    @Override
    public void hostingDisconnected(long keyId) {
        hostingService.onDisconnected(keyId);
        commanderScheduler.eligibilityChanged();
    }

    @Override
    public void hostingReportState(long keyId, long userId, String gameUsername, long tick, PlayerState state) {
        hostingService.onState(keyId, tick);
        commanderScheduler.reportState(keyId, userId, gameUsername, tick, state);
    }

    @Override
    public void hostingOnReceipt(long keyId, PlanReceipt receipt) {
        hostingService.onReceipt(keyId, receipt);
    }

    @Override
    public void hostingRegisterPlatformPlan(long keyId, ObjectNode plan) {
        hostingService.registerPlatformPlan(keyId, plan);
    }

    @Override
    public void hostingUnregisterPlatformPlan(long keyId, ObjectNode plan) {
        hostingService.unregisterPlatformPlan(keyId, plan);
    }
}
