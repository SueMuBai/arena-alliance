package com.arena.alliance.e2e;

import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.engine.CasualtyJudge;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.GameJson;
import com.arena.alliance.incident.Incident;
import com.arena.alliance.incident.IncidentRepository;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserRepository;
import com.arena.alliance.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端到端：两名成员接入 FakeArena → 成员 A 的 agent 提交攻击成员 B 的计划
 * → 平台用 A 的 key 覆盖拦截 → 之后 B 仍出现伤亡 → 裁决归因 → A 被自动踢出。
 */
@SpringBootTest
@ActiveProfiles("test")
class AllianceProtectionE2ETest {

    static final FakeArenaServer fake = new FakeArenaServer();

    @DynamicPropertySource
    static void gameProps(DynamicPropertyRegistry registry) {
        fake.start();
        registry.add("alliance.game.api-base", () -> "http://localhost:" + fake.port());
    }

    @AfterAll
    static void tearDown() {
        fake.stop();
    }

    static final String ALICE_TOKEN = "token-alice-0001";
    static final String BOB_TOKEN = "token-bob-000001";

    @Autowired
    UserService userService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ApiKeyService apiKeyService;
    @Autowired
    WorldAggregator aggregator;
    @Autowired
    CasualtyJudge judge;
    @Autowired
    IncidentRepository incidentRepository;

    @Test
    @Timeout(90)
    void blocksInternalAttackThenKicksOnCasualty() throws Exception {
        judge.graceMs = 1500;

        User alice = userService.loginFromLinuxDo("t:alice", "alice_ld", "Alice", 3, true);
        User bob = userService.loginFromLinuxDo("t:bob", "bob_ld", "Bob", 3, true);
        assertTrue(alice.isAdmin(), "首位登录用户应为管理员");
        assertEquals(User.Role.MEMBER, bob.getRole());

        apiKeyService.addKey(alice.getId(), ALICE_TOKEN, "alice主号");
        apiKeyService.addKey(bob.getId(), BOB_TOKEN, "bob主号");

        assertTrue(fake.awaitConnections(2, 20000), "两个成员会话应连上 FakeArena");

        // ---- Tick 100：alice 先锋 [10,10]，bob 工兵 [10,11]（相邻） ----
        fake.broadcastTick(100);
        fake.sendState(ALICE_TOKEN, """
                {"status":"ACTIVE","resources":10,"population":2,
                 "champion_beacon":{"position":[0,0]},
                 "objects":[
                   {"kind":"CORE","id":"a-core","controlled":true,"owner_username":"alice","position":[9,9],"hp":5,"shield":5,"state":"NORMAL"},
                   {"kind":"UNIT","id":"a-v1","controlled":true,"position":[10,10],"hp":3,"unit_type":"VANGUARD"},
                   {"kind":"UNIT","id":"a-w2","controlled":true,"position":[8,9],"hp":2,"unit_type":"WORKER","cargo":0}
                 ],"events":[]}""");
        fake.sendState(BOB_TOKEN, """
                {"status":"ACTIVE","resources":8,"population":1,
                 "champion_beacon":{"position":[0,0]},
                 "objects":[
                   {"kind":"CORE","id":"b-core","controlled":true,"owner_username":"bob","position":[12,12],"hp":5,"shield":5,"state":"NORMAL"},
                   {"kind":"UNIT","id":"b-w1","controlled":true,"position":[10,11],"hp":2,"unit_type":"WORKER","cargo":0}
                 ],"events":[]}""");

        // 等两边状态处理完（bob 工兵进入成员索引）
        waitUntil(() -> aggregator.memberIndex().byId().containsKey("b-w1")
                && aggregator.memberIndex().byId().containsKey("a-v1"), 10000);

        // ---- alice 的 agent 提交攻击计划：SWEEP DOWN 指向 bob 工兵 ----
        fake.sendReceived(ALICE_TOKEN, 100, "AGENT", """
                {"tick":100,"unit_actions":{
                   "a-v1":{"type":"SWEEP","direction":"DOWN"},
                   "a-w2":{"type":"HARVEST"}},
                 "core_action":{"type":"WAIT"}}""");

        // ---- 平台应立即用 alice 的 key 覆盖计划（攻击动作→WAIT，其余保留） ----
        FakeArenaServer.Command countermand = fake.awaitCommand(10000);
        assertNotNull(countermand, "应收到反制覆盖提交");
        assertEquals(ALICE_TOKEN, countermand.token());
        assertNotNull(countermand.idempotencyKey());
        JsonNode plan = GameJson.MAPPER.readTree(countermand.body());
        assertEquals("WAIT", plan.at("/unit_actions/a-v1/type").asText(), "攻击动作应被替换为 WAIT");
        assertEquals("HARVEST", plan.at("/unit_actions/a-w2/type").asText(), "无害动作应原样保留");
        assertEquals("WAIT", plan.at("/core_action/type").asText());
        assertEquals(100, plan.get("tick").asInt());

        // ---- Tick 101：（模拟手操攻击落地）bob 工兵阵亡，双方事件交叉印证 ----
        fake.broadcastTick(101);
        fake.sendState(ALICE_TOKEN, """
                {"status":"ACTIVE","resources":10,"population":2,"champion_beacon":{"position":[0,0]},
                 "objects":[
                   {"kind":"CORE","id":"a-core","controlled":true,"owner_username":"alice","position":[9,9],"hp":5,"shield":5,"state":"NORMAL"},
                   {"kind":"UNIT","id":"a-v1","controlled":true,"position":[10,10],"hp":3,"unit_type":"VANGUARD"}
                 ],
                 "events":[{"event_id":"ev-a1","tick":101,"event_type":"SWEEP_RESOLVED","actor_id":"a-v1","position":[10,11],"values":{"targets_hit":1}}]}""");
        fake.sendState(BOB_TOKEN, """
                {"status":"ACTIVE","resources":8,"population":0,"champion_beacon":{"position":[0,0]},
                 "objects":[
                   {"kind":"CORE","id":"b-core","controlled":true,"owner_username":"bob","position":[12,12],"hp":5,"shield":5,"state":"NORMAL"}
                 ],
                 "events":[{"event_id":"ev-b1","tick":101,"event_type":"UNIT_DAMAGED","reason_code":"ATTACK","target_id":"b-w1","position":[10,11],"values":{"damage":1,"hp":0}}]}""");

        // ---- 裁决（宽限 1.5s + 扫描 1s）：默认规则"死单位即踢" → alice 被踢 ----
        waitUntil(() -> userRepository.findById(alice.getId())
                .map(u -> u.getStatus() == User.Status.KICKED).orElse(false), 20000);

        List<String> types = incidentRepository.findTop200ByOrderByIdDesc().stream()
                .map(Incident::getType).toList();
        assertTrue(types.contains("INTERNAL_ATTACK_BLOCKED"), "应有拦截事件，实际: " + types);
        assertTrue(types.contains("INTERNAL_CASUALTY"), "应有伤亡事件，实际: " + types);
        assertTrue(types.contains("KICK"), "应有踢出事件，实际: " + types);

        // 被踢成员的 key 全部停用
        assertTrue(apiKeyService.listByUser(alice.getId()).stream()
                .noneMatch(k -> k.getStatus() == com.arena.alliance.apikey.ApiKey.Status.ENABLED));
    }

    static void waitUntil(Supplier<Boolean> condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(200);
        }
        fail("等待条件超时(" + timeoutMs + "ms)");
    }
}
