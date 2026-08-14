package com.arena.alliance.admin;

import com.arena.alliance.apikey.ApiKey;
import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.common.AllianceException;
import com.arena.alliance.common.ApiResponse;
import com.arena.alliance.engine.AllianceEngine;
import com.arena.alliance.engine.Enforcer;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.incident.IncidentService;
import com.arena.alliance.incident.IncidentType;
import com.arena.alliance.map.MapSseService;
import com.arena.alliance.rules.RuleSettings;
import com.arena.alliance.rules.RuleService;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台（AuthInterceptor 已保证仅管理员可达 /api/admin/**）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RuleService ruleService;
    private final UserService userService;
    private final ApiKeyService apiKeyService;
    private final AllianceEngine engine;
    private final WorldAggregator aggregator;
    private final Enforcer enforcer;
    private final IncidentService incidentService;
    private final MapSseService sse;

    public AdminController(RuleService ruleService, UserService userService, ApiKeyService apiKeyService,
                           AllianceEngine engine, WorldAggregator aggregator, Enforcer enforcer,
                           IncidentService incidentService, MapSseService sse) {
        this.ruleService = ruleService;
        this.userService = userService;
        this.apiKeyService = apiKeyService;
        this.engine = engine;
        this.aggregator = aggregator;
        this.enforcer = enforcer;
        this.incidentService = incidentService;
        this.sse = sse;
    }

    @GetMapping("/rules")
    public ApiResponse<RuleSettings> rules() {
        return ApiResponse.ok(ruleService.rules());
    }

    @PutMapping("/rules")
    public ApiResponse<RuleSettings> updateRules(@RequestBody JsonNode patch) {
        return ApiResponse.ok(ruleService.update(patch));
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tick", aggregator.maxTick());
        m.put("sessions", engine.sessionCount());
        m.put("sseClients", sse.clientCount());
        m.put("members", userService.listAll().size());
        return ApiResponse.ok(m);
    }

    @GetMapping("/members")
    public ApiResponse<List<Map<String, Object>>> members() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<Long, List<ApiKey>> keysByUser = new LinkedHashMap<>();
        for (ApiKey key : apiKeyService.listAll()) {
            keysByUser.computeIfAbsent(key.getUserId(), k -> new ArrayList<>()).add(key);
        }
        for (User user : userService.listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", user.getId());
            m.put("username", user.getUsername());
            m.put("displayName", user.getDisplayName());
            m.put("role", user.getRole().name());
            m.put("status", user.getStatus().name());
            m.put("warnings", user.getWarnings());
            m.put("trustLevel", user.getTrustLevel());
            m.put("createdAt", user.getCreatedAt() == null ? null : user.getCreatedAt().toString());
            m.put("lastLoginAt", user.getLastLoginAt() == null ? null : user.getLastLoginAt().toString());
            List<Map<String, Object>> keys = new ArrayList<>();
            for (ApiKey key : keysByUser.getOrDefault(user.getId(), List.of())) {
                Map<String, Object> km = new LinkedHashMap<>();
                km.put("id", key.getId());
                km.put("label", key.getLabel());
                km.put("masked", ApiKeyService.masked(key));
                km.put("gameUsername", key.getGameUsername());
                km.put("status", key.getStatus().name());
                km.put("online", engine.isKeyOnline(key.getId()));
                km.put("lastSeenTick", key.getLastSeenTick());
                keys.add(km);
            }
            m.put("keys", keys);
            out.add(m);
        }
        return ApiResponse.ok(out);
    }

    @PostMapping("/members/{id}/kick")
    public ApiResponse<Void> kick(@PathVariable long id) {
        User target = userService.get(id);
        if (target.isAdmin()) {
            throw AllianceException.forbidden("不能踢出管理员");
        }
        if (target.getStatus() == User.Status.KICKED) {
            throw AllianceException.badRequest("该成员已被踢出");
        }
        enforcer.kick(id, target.getUsername(), aggregator.maxTick(), "管理员手动移出");
        return ApiResponse.ok();
    }

    @PostMapping("/members/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable long id) {
        User target = userService.restore(id);
        incidentService.record(IncidentType.RESTORE, aggregator.maxTick(), null, null,
                id, target.getUsername(), "管理员恢复成员资格（其 key 需重新启用）");
        return ApiResponse.ok();
    }

    @PostMapping("/members/{id}/reset-warnings")
    public ApiResponse<Void> resetWarnings(@PathVariable long id) {
        userService.resetWarnings(id);
        return ApiResponse.ok();
    }

    @GetMapping("/incidents")
    public ApiResponse<List<Map<String, Object>>> incidents(@RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(incidentService.recent(limit));
    }
}
