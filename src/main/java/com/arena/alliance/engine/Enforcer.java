package com.arena.alliance.engine;

import com.arena.alliance.incident.IncidentService;
import com.arena.alliance.incident.IncidentType;
import com.arena.alliance.rules.RuleSettings;
import com.arena.alliance.rules.RuleService;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 执法：按联盟规则对内部伤亡裁决结果执行警告或踢出。
 */
@Component
public class Enforcer {

    private static final Logger log = LoggerFactory.getLogger(Enforcer.class);

    private final RuleService ruleService;
    private final UserService userService;
    private final IncidentService incidents;
    private final ApplicationEventPublisher publisher;

    public Enforcer(RuleService ruleService, UserService userService,
                    IncidentService incidents, ApplicationEventPublisher publisher) {
        this.ruleService = ruleService;
        this.userService = userService;
        this.incidents = incidents;
        this.publisher = publisher;
    }

    /** 规则判定（纯函数，可单测）：该伤亡等级是否直接踢出 */
    static boolean shouldKick(RuleSettings rules, CasualtyJudge.Severity severity) {
        return switch (severity) {
            case CORE_DESTROYED -> rules.kickOnCoreDestroyed;
            case CORE_HP -> rules.kickOnCoreDamage;
            case UNIT_DEATH -> rules.kickOnUnitDeath;
            case UNIT_HIT, SHIELD_ONLY -> false;
        };
    }

    public void onInternalCasualty(CasualtyJudge.Verdict verdict, long tick) {
        RuleSettings rules = ruleService.rules();
        incidents.record(IncidentType.INTERNAL_CASUALTY, tick,
                verdict.attackerUserId(), verdict.attackerName(),
                verdict.victimUserId(), verdict.victimName(),
                verdict.severity().label + "：" + verdict.detail());

        User attacker;
        try {
            attacker = userService.get(verdict.attackerUserId());
        } catch (Exception e) {
            log.warn("裁决攻击者 {} 不存在", verdict.attackerUserId());
            return;
        }
        if (attacker.getStatus() != User.Status.ACTIVE) {
            return;
        }

        if (shouldKick(rules, verdict.severity())) {
            kick(verdict.attackerUserId(), verdict.attackerName(), tick,
                    "内部攻击致 " + verdict.victimName() + " " + verdict.severity().label);
            return;
        }
        if (rules.warnOnShieldDamage) {
            int warnings = userService.warn(verdict.attackerUserId());
            incidents.record(IncidentType.WARNING, tick,
                    verdict.attackerUserId(), verdict.attackerName(),
                    verdict.victimUserId(), verdict.victimName(),
                    "内部攻击（" + verdict.severity().label + "），第 " + warnings + " 次警告");
            if (warnings >= rules.kickAfterWarnings) {
                kick(verdict.attackerUserId(), verdict.attackerName(), tick, "警告累计 " + warnings + " 次");
            }
        }
    }

    public void kick(long userId, String name, long tick, String reason) {
        userService.kick(userId);
        incidents.record(IncidentType.KICK, tick, userId, name, null, null, reason);
        publisher.publishEvent(new EngineEvents.MemberKicked(userId, reason));
        log.warn("成员 {}({}) 已被踢出联盟：{}", name, userId, reason);
    }
}
