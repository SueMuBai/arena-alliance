package com.arena.alliance.engine;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameEvent;
import com.arena.alliance.incident.IncidentService;
import com.arena.alliance.incident.IncidentType;
import com.arena.alliance.rules.RuleService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 伤亡法庭：把所有成员在同一 Tick 的决议事件汇成"证据"，宽限期后统一归因裁决。
 *
 * 归因途径（平台握有攻守双方 key，可交叉验证）：
 * 1. 受害者 CORE_DESTROYED.values.destroyed_by → 攻击者游戏用户名直配
 * 2. 攻击者 SHOT_HIT / DESTRUCTION_PARTICIPATION 的 target_id ∈ 受害者对象
 * 3. 攻击者 SWEEP_RESOLVED(targets_hit>0) 的格位 == 受害者受伤事件格位
 * 找不到成员攻击者 → 判为外部攻击告警。
 */
@Component
public class CasualtyJudge {

    private static final Logger log = LoggerFactory.getLogger(CasualtyJudge.class);

    /** 伤亡等级（升序） */
    public enum Severity {
        SHIELD_ONLY("仅护盾受损"),
        UNIT_HIT("单位受伤"),
        UNIT_DEATH("单位阵亡"),
        CORE_HP("Core 掉血"),
        CORE_DESTROYED("Core 被摧毁");

        public final String label;

        Severity(String label) {
            this.label = label;
        }
    }

    public record Verdict(long attackerUserId, String attackerName,
                          long victimUserId, String victimName,
                          Severity severity, String detail) {
    }

    public record ExternalHit(long victimUserId, String victimName, Severity severity, String detail) {
    }

    public record JudgeResult(List<Verdict> verdicts, List<ExternalHit> externalHits) {
    }

    record UserEvidence(long userId, String gameUsername, List<GameEvent> events) {
    }

    static final class TickCourt {
        final long tick;
        final long openedAtMs = System.currentTimeMillis();
        final Map<Long, UserEvidence> byUser = new ConcurrentHashMap<>();

        TickCourt(long tick) {
            this.tick = tick;
        }
    }

    private static final Set<String> RELEVANT_EVENTS = Set.of(
            "UNIT_DAMAGED", "CORE_DAMAGED", "CORE_DESTROYED",
            "SHOT_HIT", "SWEEP_RESOLVED", "DESTRUCTION_PARTICIPATION");

    private final Map<Long, TickCourt> courts = new ConcurrentHashMap<>();
    private final WorldAggregator aggregator;
    private final Enforcer enforcer;
    private final IncidentService incidents;
    private final RuleService ruleService;

    /** 证据收集宽限期：等所有成员的同 Tick 状态到齐（正常同一窗口内到达）；测试可调小 */
    public volatile long graceMs = 6000;

    public CasualtyJudge(WorldAggregator aggregator, Enforcer enforcer,
                         IncidentService incidents, RuleService ruleService) {
        this.aggregator = aggregator;
        this.enforcer = enforcer;
        this.incidents = incidents;
        this.ruleService = ruleService;
    }

    /** 会话收到 state 后提交本成员事件（按事件自带 tick 分桶） */
    public void submit(long userId, String gameUsername, List<GameEvent> events) {
        for (GameEvent e : events) {
            if (e.eventType() == null || !RELEVANT_EVENTS.contains(e.eventType())) {
                continue;
            }
            TickCourt court = courts.computeIfAbsent(e.tick(), TickCourt::new);
            court.byUser
                    .computeIfAbsent(userId, id -> new UserEvidence(id, gameUsername, new ArrayList<>()))
                    .events().add(e);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, TickCourt> entry : courts.entrySet()) {
            TickCourt court = entry.getValue();
            if (now - court.openedAtMs < graceMs) {
                continue;
            }
            if (courts.remove(entry.getKey()) != null) {
                try {
                    judgeCourt(court);
                } catch (Exception e) {
                    log.error("tick {} 裁决失败", court.tick, e);
                }
            }
        }
    }

    private void judgeCourt(TickCourt court) {
        Map<String, Long> memberByName = new HashMap<>();
        aggregator.members().values().forEach(m -> {
            if (m.gameUsername() != null) {
                memberByName.put(m.gameUsername(), m.userId());
            }
        });
        court.byUser.values().forEach(ev -> {
            if (ev.gameUsername() != null) {
                memberByName.put(ev.gameUsername(), ev.userId());
            }
        });

        JudgeResult result = attribute(court.tick, court.byUser.values(), aggregator::ownerOf, memberByName);

        for (Verdict v : result.verdicts()) {
            enforcer.onInternalCasualty(v, court.tick);
        }
        if (ruleService.rules().externalAlert) {
            for (ExternalHit hit : result.externalHits()) {
                incidents.record(IncidentType.EXTERNAL_ATTACK, court.tick,
                        null, null, hit.victimUserId(), hit.victimName(), hit.detail());
            }
        }
    }

    /**
     * 纯函数归因（可单测）。
     */
    static JudgeResult attribute(long tick,
                                 Collection<UserEvidence> evidences,
                                 Function<String, WorldAggregator.OwnerRef> ownerOf,
                                 Map<String, Long> memberByName) {
        record Claim(long attackerUserId, String attackerName) {
        }
        Map<String, List<Claim>> hitsByTarget = new HashMap<>();
        Map<Cell, List<Claim>> sweepsByCell = new HashMap<>();
        Map<Long, String> nameByUser = new HashMap<>();

        for (UserEvidence ev : evidences) {
            nameByUser.put(ev.userId(), ev.gameUsername() == null ? ("成员#" + ev.userId()) : ev.gameUsername());
            for (GameEvent e : ev.events()) {
                switch (e.eventType()) {
                    case "SHOT_HIT", "DESTRUCTION_PARTICIPATION" -> {
                        if (e.targetId() != null) {
                            hitsByTarget.computeIfAbsent(e.targetId(), k -> new ArrayList<>())
                                    .add(new Claim(ev.userId(), nameByUser.get(ev.userId())));
                        }
                    }
                    case "SWEEP_RESOLVED" -> {
                        if (e.valueInt("targets_hit", 0) > 0 && e.position() != null) {
                            sweepsByCell.computeIfAbsent(e.position(), k -> new ArrayList<>())
                                    .add(new Claim(ev.userId(), nameByUser.get(ev.userId())));
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        final class Agg {
            Severity max = Severity.SHIELD_ONLY;
            final List<String> notes = new ArrayList<>();

            void add(Severity s, String note) {
                if (s.compareTo(max) > 0) {
                    max = s;
                }
                if (notes.size() < 10) {
                    notes.add(note);
                }
            }
        }
        Map<Long, Map<Long, Agg>> tally = new LinkedHashMap<>();
        Map<Long, Agg> externalByVictim = new LinkedHashMap<>();

        for (UserEvidence victim : evidences) {
            for (GameEvent e : victim.events()) {
                Severity severity = severityOf(e);
                if (severity == null) {
                    continue;
                }
                String note = severityNote(e, severity);

                Set<Long> attackers = new LinkedHashSet<>();
                List<String> externalNames = new ArrayList<>();

                if (e.targetId() != null) {
                    for (Claim c : hitsByTarget.getOrDefault(e.targetId(), List.of())) {
                        if (c.attackerUserId() != victim.userId()) {
                            attackers.add(c.attackerUserId());
                            nameByUser.putIfAbsent(c.attackerUserId(), c.attackerName());
                        }
                    }
                }
                if (e.position() != null) {
                    for (Claim c : sweepsByCell.getOrDefault(e.position(), List.of())) {
                        if (c.attackerUserId() != victim.userId()) {
                            attackers.add(c.attackerUserId());
                            nameByUser.putIfAbsent(c.attackerUserId(), c.attackerName());
                        }
                    }
                }
                if ("CORE_DESTROYED".equals(e.eventType()) && e.values() != null) {
                    JsonNode names = e.values().get("destroyed_by");
                    if (names != null && names.isArray()) {
                        for (JsonNode n : names) {
                            String name = n.asText();
                            Long uid = memberByName.get(name);
                            if (uid != null && uid != victim.userId()) {
                                attackers.add(uid);
                                nameByUser.putIfAbsent(uid, name);
                            } else if (uid == null) {
                                externalNames.add(name);
                            }
                        }
                    }
                }

                if (attackers.isEmpty()) {
                    String extNote = externalNames.isEmpty() ? note : note + "（攻击者: " + String.join(",", externalNames) + "）";
                    externalByVictim.computeIfAbsent(victim.userId(), k -> new Agg()).add(severity, extNote);
                } else {
                    for (long attacker : attackers) {
                        tally.computeIfAbsent(attacker, k -> new LinkedHashMap<>())
                                .computeIfAbsent(victim.userId(), k -> new Agg())
                                .add(severity, note);
                    }
                }
            }
        }

        List<Verdict> verdicts = new ArrayList<>();
        tally.forEach((attackerId, victims) -> victims.forEach((victimId, agg) ->
                verdicts.add(new Verdict(attackerId, nameByUser.getOrDefault(attackerId, "成员#" + attackerId),
                        victimId, nameByUser.getOrDefault(victimId, "成员#" + victimId),
                        agg.max, String.join("；", agg.notes)))));

        List<ExternalHit> external = new ArrayList<>();
        externalByVictim.forEach((victimId, agg) ->
                external.add(new ExternalHit(victimId, nameByUser.getOrDefault(victimId, "成员#" + victimId),
                        agg.max, String.join("；", agg.notes))));

        return new JudgeResult(verdicts, external);
    }

    private static Severity severityOf(GameEvent e) {
        return switch (e.eventType()) {
            case "UNIT_DAMAGED" -> e.valueInt("hp", -1) == 0 ? Severity.UNIT_DEATH : Severity.UNIT_HIT;
            case "CORE_DAMAGED" -> e.valueInt("hp_damage", 0) > 0 ? Severity.CORE_HP : Severity.SHIELD_ONLY;
            case "CORE_DESTROYED" -> "ATTACK".equals(e.reasonCode()) ? Severity.CORE_DESTROYED : null;
            default -> null;
        };
    }

    private static String severityNote(GameEvent e, Severity severity) {
        StringBuilder sb = new StringBuilder(severity.label);
        if (e.values() != null && e.values().has("damage")) {
            sb.append("(-").append(e.valueInt("damage", 0)).append(")");
        }
        if (e.position() != null) {
            sb.append("@").append(e.position());
        }
        return sb.toString();
    }
}
