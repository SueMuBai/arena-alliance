package com.arena.alliance.e2e;

import com.arena.alliance.apikey.ApiKey;
import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.hosting.HostingService;
import com.arena.alliance.hosting.HostingStatus;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 人工接管全链路：登录 → 上传 key → 开托管 → 收到游戏状态 →
 * 列出可操控账号 → 查询单位可执行动作 → 下达指令。
 * 复现"点击自己的单位没有反应"这类问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ManualControlE2ETest {

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

    static final String TOKEN = "token-manual-0001";
    static final String UNIT_ID = "8692e923-db2f-4ccf-88b0-c8c91fce35af";
    static final String CORE_ID = "11111111-db2f-4ccf-88b0-c8c91fce35af";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserService userService;
    @Autowired
    ApiKeyService apiKeyService;
    @Autowired
    HostingService hostingService;

    @Test
    @Timeout(90)
    void manualPanelAndCommandWorkEndToEnd() throws Exception {
        // 用开发登录建号并拿会话，保证操作者与 key 属主是同一个用户
        Cookie session = login();
        User user = userService.listAll().stream()
                .filter(u -> "manual_ld".equals(u.getUsername())).findFirst().orElseThrow();

        ApiKey key = apiKeyService.addKey(user.getId(), TOKEN, "手动测试");
        assertTrue(fake.awaitConnections(1, 20000));
        ApiKey enabled = apiKeyService.updateSettings(user.getId(), key.getId(), "手动测试",
                true, false, true, "{\"mode\":\"BALANCED\"}");
        hostingService.applySettings(enabled, false);
        assertEquals(HostingStatus.WAITING_STATE, hostingService.statusOf(key.getId()));

        // 游戏推送一份完整状态：Core + 一个空载 Worker
        fake.broadcastTick(300);
        fake.sendState(TOKEN, """
                {"status":"ACTIVE","resources":12,"population":1,
                 "champion_beacon":{"position":[0,0]},
                 "objects":[
                   {"kind":"CORE","id":"%s","controlled":true,"owner_username":"manual","position":[5,5],"hp":5,"shield":5,"state":"NORMAL"},
                   {"kind":"UNIT","id":"%s","controlled":true,"position":[6,5],"hp":2,"unit_type":"WORKER","cargo":0}
                 ],"events":[]}""".formatted(CORE_ID, UNIT_ID));

        // 等状态进入聚合器
        waitUntil(() -> {
            try {
                return mockMvc.perform(get("/api/hosting/controllable").cookie(session))
                        .andReturn().getResponse().getContentAsString().contains("\"keyId\":" + key.getId());
            } catch (Exception e) {
                return false;
            }
        }, 15000);

        // ① 可操控账号列表
        String controllable = mockMvc.perform(get("/api/hosting/controllable").cookie(session))
                .andReturn().getResponse().getContentAsString();
        assertTrue(controllable.contains("\"success\":true"), controllable);

        // ② 点击单位 → 查询可执行动作（这是页面上"点了没反应"的那个请求）
        MvcResult actionsResult = mockMvc.perform(
                        get("/api/hosting/{keyId}/objects/{objectId}/actions", key.getId(), UNIT_ID)
                                .cookie(session))
                .andReturn();
        String body = actionsResult.getResponse().getContentAsString();
        assertEquals(200, actionsResult.getResponse().getStatus(), "查询动作失败: " + body);
        assertTrue(body.contains("\"success\":true"), body);
        assertTrue(body.contains("\"MOVE\""), "应包含移动动作: " + body);
        assertTrue(body.contains("\"HARVEST\""), "Worker 应包含采集动作: " + body);
        assertTrue(body.contains("\"targets\""), "移动应带目标格: " + body);

        // ③ Core 的动作（含 4-Tick 迁移提示）
        String coreBody = mockMvc.perform(
                        get("/api/hosting/{keyId}/objects/{objectId}/actions", key.getId(), CORE_ID)
                                .cookie(session))
                .andReturn().getResponse().getContentAsString();
        assertTrue(coreBody.contains("START_MOVE"), coreBody);
        assertTrue(coreBody.contains("SPAWN"), coreBody);

        // ④ 下达一条人工指令
        MvcResult exec = mockMvc.perform(post("/api/hosting/{keyId}/action", key.getId())
                        .cookie(session)
                        .contentType("application/json")
                        .content("{\"expectedTick\":300,\"targetId\":\"" + UNIT_ID
                                + "\",\"action\":{\"type\":\"MOVE\",\"direction\":\"RIGHT\"}}"))
                .andReturn();
        assertEquals(202, exec.getResponse().getStatus(), exec.getResponse().getContentAsString());

        FakeArenaServer.Command cmd = awaitCommandContaining(UNIT_ID, "RIGHT", 10000);
        assertNotNull(cmd, "人工指令应被提交到游戏服务器");
        assertTrue(cmd.body().contains(UNIT_ID) && cmd.body().contains("RIGHT"),
                "提交内容应包含手动动作: " + cmd.body());

        // ⑤ 违反联盟规则的指令应被拒绝并提示
        String violation = mockMvc.perform(post("/api/hosting/{keyId}/action", key.getId())
                        .cookie(session)
                        .contentType("application/json")
                        .content("{\"expectedTick\":300,\"targetId\":\"" + UNIT_ID
                                + "\",\"action\":{\"type\":\"SWEEP\",\"direction\":\"UP\"}}"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(violation.contains("\"success\":false"), "Worker 不能扫击，应被拒绝: " + violation);
    }

    private Cookie login() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/dev-login").param("u", "manual_ld")).andReturn();
        Cookie cookie = result.getResponse().getCookie("alliance_session");
        assertNotNull(cookie, "开发登录应下发会话 Cookie");
        return cookie;
    }

    private static FakeArenaServer.Command awaitCommandContaining(
            String first, String second, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            FakeArenaServer.Command command = fake.awaitCommand(
                    Math.max(1, deadline - System.currentTimeMillis()));
            if (command == null) return null;
            if (command.body().contains(first) && command.body().contains(second)) return command;
        }
        return null;
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
