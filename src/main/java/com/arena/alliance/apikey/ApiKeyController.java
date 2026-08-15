package com.arena.alliance.apikey;

import com.arena.alliance.auth.CurrentUser;
import com.arena.alliance.common.AllianceException;
import com.arena.alliance.common.ApiResponse;
import com.arena.alliance.engine.AllianceEngine;
import com.arena.alliance.hosting.HostingConfig;
import com.arena.alliance.hosting.HostingService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {

    public record AddKeyRequest(@NotBlank String token, String label) {
    }

    public record PrivacyRequest(Boolean showCoreOnMap, Boolean rosterIdsOnly) {
    }

    /** 编辑抽屉统一保存：备注 + 隐私 + 托管 */
    public record SettingsRequest(String label, Boolean showCoreOnMap, Boolean rosterIdsOnly,
                                  Boolean hostingEnabled, JsonNode hostingConfig) {
    }

    private final ApiKeyService apiKeyService;
    private final AllianceEngine engine;
    private final com.arena.alliance.auth.SessionService sessionService;
    private final HostingService hostingService;
    private final com.arena.alliance.user.UserService userService;

    public ApiKeyController(ApiKeyService apiKeyService, AllianceEngine engine,
                            com.arena.alliance.auth.SessionService sessionService,
                            HostingService hostingService,
                            com.arena.alliance.user.UserService userService) {
        this.apiKeyService = apiKeyService;
        this.engine = engine;
        this.sessionService = sessionService;
        this.hostingService = hostingService;
        this.userService = userService;
    }

    /** 当前用户的 agent 机读接入令牌（盟友名册接口用） */
    @GetMapping("/roster-token")
    public ApiResponse<Map<String, Object>> rosterToken(HttpServletRequest request) {
        CurrentUser me = current(request);
        apiKeyService.requireUploadedKey(me.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", sessionService.issueRosterToken(me.id(), userService.get(me.id()).getRosterTokenVersion()));
        return ApiResponse.ok(m);
    }

    /** 重置接入令牌：旧令牌立即失效（怀疑泄露时使用） */
    @PostMapping("/roster-token/reset")
    public ApiResponse<Map<String, Object>> resetRosterToken(HttpServletRequest request) {
        CurrentUser me = current(request);
        apiKeyService.requireUploadedKey(me.id());
        int version = userService.rotateRosterToken(me.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", sessionService.issueRosterToken(me.id(), version));
        return ApiResponse.ok(m);
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> myKeys(HttpServletRequest request) {
        CurrentUser me = current(request);
        List<Map<String, Object>> list = apiKeyService.listByUser(me.id()).stream()
                .map(this::view)
                .toList();
        return ApiResponse.ok(list);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> add(HttpServletRequest request,
                                                @RequestBody AddKeyRequest body) {
        CurrentUser me = current(request);
        ApiKey key = apiKeyService.addKey(me.id(), body.token(), body.label());
        return ApiResponse.ok(view(key));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable long id) {
        CurrentUser me = current(request);
        apiKeyService.deleteKey(me.id(), me.admin(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<Map<String, Object>> enable(HttpServletRequest request, @PathVariable long id) {
        CurrentUser me = current(request);
        return ApiResponse.ok(view(apiKeyService.setEnabled(me.id(), me.admin(), id, true)));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Map<String, Object>> disable(HttpServletRequest request, @PathVariable long id) {
        CurrentUser me = current(request);
        return ApiResponse.ok(view(apiKeyService.setEnabled(me.id(), me.admin(), id, false)));
    }

    @PostMapping("/{id}/privacy")
    public ApiResponse<Map<String, Object>> privacy(HttpServletRequest request, @PathVariable long id,
                                                    @RequestBody PrivacyRequest body) {
        if (body == null || body.showCoreOnMap() == null || body.rosterIdsOnly() == null) {
            throw AllianceException.badRequest("隐私设置不完整");
        }
        CurrentUser me = current(request);
        return ApiResponse.ok(view(apiKeyService.setPrivacy(me.id(), id,
                body.showCoreOnMap(), body.rosterIdsOnly())));
    }

    @PutMapping("/{id}/settings")
    public ApiResponse<Map<String, Object>> settings(HttpServletRequest request, @PathVariable long id,
                                                     @RequestBody SettingsRequest body) {
        if (body == null || body.showCoreOnMap() == null || body.rosterIdsOnly() == null
                || body.hostingEnabled() == null) {
            throw AllianceException.badRequest("设置不完整");
        }
        CurrentUser me = current(request);
        boolean wasEnabled = apiKeyService.find(id).isHostingEnabled();
        // 经 HostingConfig 解析归一化（校验 mode/参数边界）后落库
        String configJson = HostingConfig
                .parse(body.hostingConfig() == null ? null : body.hostingConfig().toString())
                .toJson();
        ApiKey key = apiKeyService.updateSettings(me.id(), id, body.label(),
                body.showCoreOnMap(), body.rosterIdsOnly(), body.hostingEnabled(), configJson);
        hostingService.applySettings(key, wasEnabled);
        return ApiResponse.ok(view(key));
    }

    @PostMapping("/{id}/hosting/resume")
    public ApiResponse<Map<String, Object>> resumeHosting(HttpServletRequest request, @PathVariable long id) {
        CurrentUser me = current(request);
        ApiKey key = apiKeyService.find(id);
        if (!key.getUserId().equals(me.id()) && !me.admin()) {
            throw AllianceException.forbidden("无权操作他人的托管");
        }
        hostingService.resume(id);
        return ApiResponse.ok(view(key));
    }

    private Map<String, Object> view(ApiKey key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", key.getId());
        m.put("label", key.getLabel());
        m.put("masked", ApiKeyService.masked(key));
        m.put("gameUsername", key.getGameUsername());
        m.put("status", key.getStatus().name());
        m.put("online", engine.isKeyOnline(key.getId()));
        m.put("lastSeenTick", key.getLastSeenTick());
        m.put("lastError", key.getLastError());
        m.put("showCoreOnMap", key.isShowCoreOnMap());
        m.put("rosterIdsOnly", key.isRosterIdsOnly());
        HostingConfig hostingConfig = HostingConfig.parse(key.getHostingConfig());
        m.put("hostingEnabled", key.isHostingEnabled());
        m.put("hostingMode", hostingConfig.mode().name());
        m.put("hostingModeIcon", hostingConfig.mode().icon());
        m.put("hostingModeLabel", hostingConfig.mode().label());
        m.put("hostingStatus", hostingService.statusOf(key.getId()).name());
        m.put("createdAt", key.getCreatedAt() == null ? null : key.getCreatedAt().toString());
        return m;
    }

    private static CurrentUser current(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute(CurrentUser.ATTR);
    }
}
