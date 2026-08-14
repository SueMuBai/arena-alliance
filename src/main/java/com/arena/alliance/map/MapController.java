package com.arena.alliance.map;

import com.arena.alliance.auth.CurrentUser;
import com.arena.alliance.common.ApiResponse;
import com.arena.alliance.incident.IncidentService;
import com.arena.alliance.rules.RuleSettings;
import com.arena.alliance.rules.RuleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/map")
public class MapController {

    private final MapSnapshotService snapshotService;
    private final MapSseService sse;
    private final IncidentService incidentService;
    private final RuleService ruleService;

    public MapController(MapSnapshotService snapshotService, MapSseService sse,
                         IncidentService incidentService, RuleService ruleService) {
        this.snapshotService = snapshotService;
        this.sse = sse;
        this.incidentService = incidentService;
        this.ruleService = ruleService;
    }

    @GetMapping("/snapshot")
    public ApiResponse<Map<String, Object>> snapshot(HttpServletRequest request) {
        return ApiResponse.ok(snapshotService.build(current(request).id()));
    }

    /** 当前生效的联盟规则（成员可见，公约页展示用） */
    @GetMapping("/rules")
    public ApiResponse<RuleSettings> rules() {
        return ApiResponse.ok(ruleService.rules());
    }

    @GetMapping("/incidents")
    public ApiResponse<List<Map<String, Object>>> incidents(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(incidentService.recent(limit));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        return sse.subscribe(current(request).id());
    }

    private static CurrentUser current(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute(CurrentUser.ATTR);
    }
}
