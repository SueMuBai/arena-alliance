package com.arena.alliance.e2e;

import com.arena.alliance.apikey.ApiKey;
import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.game.GameJson;
import com.arena.alliance.hosting.HostingService;
import com.arena.alliance.hosting.HostingStatus;
import com.arena.alliance.incident.Incident;
import com.arena.alliance.incident.IncidentRepository;
import com.arena.alliance.user.User;
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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 联盟指挥官 E2E：两个托管账号协同采集（资源分片不互抢）+ 自动生产；
 * 检测到外部 AGENT 计划 → 单账号冲突暂停，不影响另一账号继续受指挥。
 */
@SpringBootTest
@ActiveProfiles("test")
class HostingCommanderE2ETest {

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

    static final String CAROL_TOKEN = "token-carol-0001";
    static final String DAVE_TOKEN = "token-dave-00001";

    @Autowired
    UserService userService;
    @Autowired
    ApiKeyService apiKeyService;
    @Autowired
    HostingService hostingService;
    @Autowired
    IncidentRepository incidentRepository;

    @Test
    @Timeout(90)
    void commanderCoordinatesHarvestAndIsolatesConflictPause() throws Exception {
        User carol = userService.loginFromLinuxDo("t:carol", "carol_ld", "Carol", 3, true);
        User dave = userService.loginFromLinuxDo("t:dave", "dave_ld", "Dave", 3, true);
        ApiKey carolKey = apiKeyService.addKey(carol.getId(), CAROL_TOKEN, "carol");
        ApiKey daveKey = apiKeyService.addKey(dave.getId(), DAVE_TOKEN, "dave");
        assertTrue(fake.awaitConnections(2, 20000), "两个会话应连上 FakeArena");

        // 开启托管（BALANCED 模式）
        enableHosting(carol.getId(), carolKey.getId());
        enableHosting(dave.getId(), daveKey.getId());
        assertEquals(HostingStatus.WAITING_STATE, hostingService.statusOf(carolKey.getId()));
        assertEquals(HostingStatus.WAITING_STATE, hostingService.statusOf(daveKey.getId()));

        // ---- Tick 200：两账号各带一个空载 worker，各自附近一个资源点 ----
        fake.broadcastTick(200);
        fake.sendState(CAROL_TOKEN, stateJson("carol", "c", 0, 10));
        fake.sendState(DAVE_TOKEN, stateJson("dave", "d", 20, 10));

        // 指挥官应为两个账号各提交一份计划（host- 幂等键）
        Map<String, JsonNode> plans = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            FakeArenaServer.Command cmd = fake.awaitCommand(10000);
            assertNotNull(cmd, "应收到托管计划提交 " + (i + 1));
            assertTrue(cmd.idempotencyKey().startsWith("host-"), "幂等键应为 host- 前缀: " + cmd.idempotencyKey());
            plans.put(cmd.token(), GameJson.MAPPER.readTree(cmd.body()));
        }
        assertEquals(2, plans.size(), "两个账号各一份计划");

        // 协同采集：各自的 worker 向自己附近的资源移动（RIGHT），不互抢
        JsonNode carolPlan = plans.get(CAROL_TOKEN);
        JsonNode davePlan = plans.get(DAVE_TOKEN);
        assertEquals("MOVE", carolPlan.at("/unit_actions/c-w1/type").asText());
        assertEquals("RIGHT", carolPlan.at("/unit_actions/c-w1/direction").asText());
        assertEquals("MOVE", davePlan.at("/unit_actions/d-w1/type").asText());
        assertEquals("RIGHT", davePlan.at("/unit_actions/d-w1/direction").asText());
        // 自动生产：经济阶段先补 Worker
        assertEquals("SPAWN", carolPlan.at("/core_action/type").asText());
        assertEquals("WORKER", carolPlan.at("/core_action/unit_type").asText());

        // ---- 冲突检测：carol 出现外部 AGENT 计划 → 仅 carol 暂停 ----
        fake.sendReceived(CAROL_TOKEN, 200, "AGENT",
                "{\"tick\":200,\"unit_actions\":{\"c-w1\":{\"type\":\"MOVE\",\"direction\":\"LEFT\"}}}");
        waitUntil(() -> hostingService.statusOf(carolKey.getId()) == HostingStatus.PAUSED_CONFLICT, 10000);
        assertEquals(HostingStatus.RUNNING, hostingService.statusOf(daveKey.getId()), "dave 不受影响");
        assertTrue(incidentRepository.findTop200ByOrderByIdDesc().stream()
                .map(Incident::getType).anyMatch("HOSTING_PAUSED_CONFLICT"::equals));

        // ---- Tick 201：只有 dave 继续被指挥 ----
        fake.broadcastTick(201);
        fake.sendState(CAROL_TOKEN, stateJson("carol", "c", 0, 10));
        fake.sendState(DAVE_TOKEN, stateJson("dave", "d", 20, 10));

        FakeArenaServer.Command daveOnly = fake.awaitCommand(10000);
        assertNotNull(daveOnly, "dave 应继续收到指挥计划");
        assertEquals(DAVE_TOKEN, daveOnly.token());
        assertNull(fake.awaitCommand(2500), "暂停中的 carol 不应再有托管提交");
    }

    private void enableHosting(long userId, long keyId) {
        ApiKey updated = apiKeyService.updateSettings(userId, keyId, null, true, false,
                true, "{\"mode\":\"BALANCED\"}");
        hostingService.applySettings(updated, false);
    }

    /** 账号状态：Core@[x0,0] + 空载 worker@[x0,1]，附近资源 [x0+3,1] */
    private static String stateJson(String owner, String prefix, long x0, long resourcesHeld) {
        return """
                {"status":"ACTIVE","resources":%d,"population":1,
                 "champion_beacon":{"position":[0,0]},
                 "objects":[
                   {"kind":"CORE","id":"%s-core","controlled":true,"owner_username":"%s","position":[%d,0],"hp":5,"shield":5,"state":"NORMAL"},
                   {"kind":"UNIT","id":"%s-w1","controlled":true,"position":[%d,1],"hp":2,"unit_type":"WORKER","cargo":0},
                   {"kind":"RESOURCE","positions":[[%d,1]]}
                 ],"events":[]}""".formatted(resourcesHeld, prefix, owner, x0, prefix, x0, x0 + 3);
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
