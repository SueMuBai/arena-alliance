package com.arena.alliance.map;

import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.engine.AllianceEngine;
import com.arena.alliance.engine.EngineEvents;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.rules.RuleService;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把聚合世界组装成前端快照 JSON；世界版本变化时经 SSE 推送。
 */
@Service
public class MapSnapshotService {

    private final WorldAggregator aggregator;
    private final AllianceEngine engine;
    private final RuleService ruleService;
    private final UserService userService;
    private final ApiKeyService apiKeyService;
    private final MapSseService sse;

    private volatile long lastPushedVersion = -1;

    public MapSnapshotService(WorldAggregator aggregator, AllianceEngine engine, RuleService ruleService,
                              UserService userService, ApiKeyService apiKeyService, MapSseService sse) {
        this.aggregator = aggregator;
        this.engine = engine;
        this.ruleService = ruleService;
        this.userService = userService;
        this.apiKeyService = apiKeyService;
        this.sse = sse;
    }

    @Scheduled(fixedDelay = 1500)
    public void pump() {
        long version = aggregator.version();
        if (version == lastPushedVersion || sse.clientCount() == 0) {
            return;
        }
        lastPushedVersion = version;
        sse.broadcastSnapshots(userId -> build(userId));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKeyPrivacyChanged(EngineEvents.KeyPrivacyChanged event) {
        // 下一次 pump 即使游戏世界未变化，也要重新组装一次快照。
        lastPushedVersion = -1;
    }

    /** 无查看者上下文时按最严格隐私生成，主要用于内部检查和测试。 */
    public Map<String, Object> build() {
        return build(null);
    }

    /** 为指定平台账号生成快照：隐藏 Core 对所有其他账号生效，但所有者自己始终可见。 */
    public Map<String, Object> build(long viewerUserId) {
        return build(Long.valueOf(viewerUserId));
    }

    private Map<String, Object> build(Long viewerUserId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tick", aggregator.maxTick());
        snapshot.put("allianceName", ruleService.rules().allianceName);
        snapshot.put("updatedAt", Instant.now().toString());

        Map<Long, User> users = new HashMap<>();
        for (User u : userService.listAll()) {
            users.put(u.getId(), u);
        }

        Map<Long, WorldAggregator.MemberSnapshot> memberSnapshots = aggregator.members();
        Map<Long, ApiKeyService.PrivacySettings> privacyByKey =
                apiKeyService.privacySettings(memberSnapshots.keySet());
        Set<String> allianceCoreIds = new HashSet<>();
        Set<String> allianceCoreOwners = new HashSet<>();

        List<Map<String, Object>> members = new ArrayList<>();
        for (WorldAggregator.MemberSnapshot m : memberSnapshots.values()) {
            ApiKeyService.PrivacySettings privacy = privacyByKey.getOrDefault(
                    m.keyId(), ApiKeyService.PrivacySettings.DEFAULT);
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("keyId", m.keyId());
            mm.put("userId", m.userId());
            mm.put("gameUsername", m.gameUsername());
            User owner = users.get(m.userId());
            mm.put("memberName", owner == null ? null : owner.getUsername());
            mm.put("userStatus", owner == null ? null : owner.getStatus().name());
            mm.put("warnings", owner == null ? 0 : owner.getWarnings());
            mm.put("online", engine.isKeyOnline(m.keyId()));
            mm.put("tick", m.tick());
            PlayerState state = m.state();
            mm.put("status", state.status());
            mm.put("population", state.population());
            mm.put("resources", state.resources());
            GameObject core = state.controlledCore();
            if (core != null && core.id() != null) {
                allianceCoreIds.add(core.id());
            }
            if (core != null && m.gameUsername() != null) {
                allianceCoreOwners.add(m.gameUsername());
            }
            boolean ownerViewing = viewerUserId != null && viewerUserId == m.userId();
            if (core != null && (privacy.showCoreOnMap() || ownerViewing)) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", core.id());
                cm.put("pos", cells(core.position()));
                cm.put("hp", core.hp());
                cm.put("shield", core.shield());
                cm.put("state", core.state());
                mm.put("core", cm);
            }
            List<Map<String, Object>> units = new ArrayList<>();
            if (state.objects() != null) {
                for (GameObject o : state.objects()) {
                    if (o.isUnit() && o.isControlled()) {
                        Map<String, Object> um = new LinkedHashMap<>();
                        um.put("id", o.id());
                        um.put("pos", cells(o.position()));
                        um.put("hp", o.hp());
                        um.put("type", o.unitType());
                        if (o.cargo() != null) {
                            um.put("cargo", o.cargo());
                        }
                        units.add(um);
                    }
                }
            }
            mm.put("units", units);
            members.add(mm);
        }
        snapshot.put("members", members);

        List<Map<String, Object>> enemies = new ArrayList<>();
        for (WorldAggregator.EnemySighting e : aggregator.enemies().values()) {
            if (allianceCoreIds.contains(e.id())
                    || ("CORE".equals(e.kind()) && allianceCoreOwners.contains(e.ownerUsername()))) {
                continue;
            }
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("id", e.id());
            em.put("kind", e.kind());
            em.put("type", e.unitType());
            em.put("owner", e.ownerUsername());
            em.put("pos", cells(e.pos()));
            em.put("hp", e.hp());
            em.put("tick", e.tick());
            enemies.add(em);
        }
        snapshot.put("enemies", enemies);

        List<long[]> obstacles = new ArrayList<>();
        for (Cell c : aggregator.obstacles().keySet()) {
            obstacles.add(new long[]{c.x(), c.y()});
        }
        snapshot.put("obstacles", obstacles);

        List<long[]> resources = new ArrayList<>();
        aggregator.resources().forEach((cell, observedTick) ->
                resources.add(new long[]{cell.x(), cell.y()}));
        snapshot.put("resources", resources);

        PlayerState.Beacon beacon = aggregator.beacon();
        if (beacon != null) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("pos", cells(beacon.position()));
            bm.put("status", beacon.status());
            bm.put("carrier", beacon.carrierId());
            snapshot.put("beacon", bm);
        }
        return snapshot;
    }

    private static long[] cells(Cell c) {
        return c == null ? null : new long[]{c.x(), c.y()};
    }
}
