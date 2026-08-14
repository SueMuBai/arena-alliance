package com.arena.alliance.map;

import com.arena.alliance.auth.SessionService;
import com.arena.alliance.common.AllianceException;
import com.arena.alliance.common.ApiResponse;
import com.arena.alliance.engine.AllianceEngine;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盟友名册接口：供成员自己的 agent 判断"某目标是否盟友"，避免误伤触发执法。
 * 认证使用个人接入令牌（「联盟规则」页可查看）：
 *   GET /api/alliance/roster?token=xxx   或   Authorization: Bearer xxx
 */
@RestController
@RequestMapping("/api/alliance")
public class AllianceRosterController {

    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final WorldAggregator aggregator;
    private final AllianceEngine engine;

    public AllianceRosterController(SessionService sessionService, UserRepository userRepository,
                                    WorldAggregator aggregator, AllianceEngine engine) {
        this.sessionService = sessionService;
        this.userRepository = userRepository;
        this.aggregator = aggregator;
        this.engine = engine;
    }

    @GetMapping("/roster")
    public ApiResponse<Map<String, Object>> roster(
            @RequestParam(required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String raw = token;
        if (raw == null && authorization != null && authorization.startsWith("Bearer ")) {
            raw = authorization.substring(7);
        }
        Long userId = sessionService.verifyRosterToken(raw);
        if (userId == null) {
            throw AllianceException.unauthorized("无效的接入令牌");
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() != User.Status.ACTIVE) {
            throw AllianceException.forbidden("成员资格已失效");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tick", aggregator.maxTick());
        Set<String> gameUsernames = new LinkedHashSet<>();
        List<Map<String, Object>> allies = new ArrayList<>();

        for (WorldAggregator.MemberSnapshot m : aggregator.members().values()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("gameUsername", m.gameUsername());
            am.put("userId", m.userId());
            am.put("online", engine.isKeyOnline(m.keyId()));
            am.put("tick", m.tick());
            if (m.gameUsername() != null) {
                gameUsernames.add(m.gameUsername());
            }
            List<String> objectIds = new ArrayList<>();
            List<Map<String, Object>> units = new ArrayList<>();
            if (m.state() != null && m.state().objects() != null) {
                for (GameObject o : m.state().objects()) {
                    if (!o.isControlled() || o.id() == null) {
                        continue;
                    }
                    objectIds.add(o.id());
                    if (o.isCore()) {
                        Map<String, Object> cm = new LinkedHashMap<>();
                        cm.put("id", o.id());
                        cm.put("pos", pos(o.position()));
                        am.put("core", cm);
                    } else if (o.isUnit()) {
                        Map<String, Object> um = new LinkedHashMap<>();
                        um.put("id", o.id());
                        um.put("pos", pos(o.position()));
                        um.put("type", o.unitType());
                        units.add(um);
                    }
                }
            }
            am.put("objectIds", objectIds);
            am.put("units", units);
            allies.add(am);
        }
        data.put("gameUsernames", gameUsernames);
        data.put("allies", allies);
        return ApiResponse.ok(data);
    }

    private static long[] pos(Cell c) {
        return c == null ? null : new long[]{c.x(), c.y()};
    }
}
