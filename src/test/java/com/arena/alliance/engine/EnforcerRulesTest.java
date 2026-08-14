package com.arena.alliance.engine;

import com.arena.alliance.rules.RuleSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerRulesTest {

    @Test
    void defaultRulesKickOnDeathAndCoreDamage() {
        RuleSettings rules = new RuleSettings();
        assertTrue(Enforcer.shouldKick(rules, CasualtyJudge.Severity.UNIT_DEATH));
        assertTrue(Enforcer.shouldKick(rules, CasualtyJudge.Severity.CORE_HP));
        assertTrue(Enforcer.shouldKick(rules, CasualtyJudge.Severity.CORE_DESTROYED));
        assertFalse(Enforcer.shouldKick(rules, CasualtyJudge.Severity.UNIT_HIT));
        assertFalse(Enforcer.shouldKick(rules, CasualtyJudge.Severity.SHIELD_ONLY));
    }

    @Test
    void relaxedRulesOnlyKickOnCoreDestroyed() {
        RuleSettings rules = new RuleSettings();
        rules.kickOnUnitDeath = false;
        rules.kickOnCoreDamage = false;
        assertFalse(Enforcer.shouldKick(rules, CasualtyJudge.Severity.UNIT_DEATH));
        assertFalse(Enforcer.shouldKick(rules, CasualtyJudge.Severity.CORE_HP));
        assertTrue(Enforcer.shouldKick(rules, CasualtyJudge.Severity.CORE_DESTROYED));
    }
}
