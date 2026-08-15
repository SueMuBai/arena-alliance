package com.arena.alliance.hosting;

import com.arena.alliance.auth.CurrentUser;
import com.arena.alliance.common.AllianceException;
import com.arena.alliance.common.ApiResponse;
import com.arena.alliance.engine.ThreatMonitor;
import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.GameJson;
import com.arena.alliance.game.GameCommandClient;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.GridMath;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.game.dto.UnitType;
import com.arena.alliance.hosting.plan.ProductionPlanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 人工接管接口：托管运行中的账号可由本人临时手动操控。
 * 手动指令按对象覆盖指挥官的安排——未被手动操作的单位继续听指挥。
 * 所有指令在提交前都要过联盟红线（不得攻击盟友），违规直接拒绝并提示。
 */
@RestController
@RequestMapping("/api/hosting")
public class HostingController {

    private static final Logger log = LoggerFactory.getLogger(HostingController.class);

    public record ManualActionRequest(Long expectedTick, String targetId, JsonNode action) {
    }

    private final HostingService hostingService;
    private final WorldAggregator aggregator;
    /**
     * 人工指令限流：一个 Tick 约 15 秒，正常操作远低于此。
     * 会话若被抓包复用，也无法高频刷指令。
     */
    private final com.arena.alliance.common.RateLimiter actionLimiter =
            new com.arena.alliance.common.RateLimiter(30, java.time.Duration.ofSeconds(15));

    public HostingController(HostingService hostingService, WorldAggregator aggregator) {
        this.hostingService = hostingService;
        this.aggregator = aggregator;
    }

    /** 当前用户可手动操控的账号（仅托管运行中的自己的 key） */
    @GetMapping("/controllable")
    public ApiResponse<List<Map<String, Object>>> controllable(HttpServletRequest request) {
        CurrentUser me = current(request);
        List<Map<String, Object>> list = new ArrayList<>();
        for (WorldAggregator.MemberSnapshot m : aggregator.members().values()) {
            if (m.userId() != me.id() || hostingService.statusOf(m.keyId()) != HostingStatus.RUNNING) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyId", m.keyId());
            item.put("gameUsername", m.gameUsername());
            item.put("tick", m.tick());
            item.put("mode", hostingService.configOf(m.keyId()).mode().name());
            item.put("manualObjectIds", hostingService.manuallyControlled(m.keyId(), m.tick()));
            list.add(item);
        }
        return ApiResponse.ok(list);
    }

    /** 某个对象当前可执行的动作与合法目标格（前端据此高亮） */
    @GetMapping("/{keyId}/objects/{objectId}/actions")
    public ApiResponse<Map<String, Object>> actions(HttpServletRequest request,
                                                    @PathVariable long keyId,
                                                    @PathVariable String objectId) {
        CurrentUser me = current(request);
        WorldAggregator.MemberSnapshot snapshot = requireControllable(me, keyId);
        GameObject object = findOwnObject(snapshot.state(), objectId);
        requireWebSafe(object.position());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyId", keyId);
        data.put("tick", snapshot.tick());
        data.put("objectId", objectId);
        data.put("controlId", object.isCore() ? "CORE" : objectId);
        data.put("kind", object.kind());
        data.put("unitType", object.unitType());
        data.put("pos", object.position() == null ? null
                : new long[]{object.position().x(), object.position().y()});
        data.put("hp", object.hp());
        data.put("shield", object.shield());
        data.put("cargo", object.cargo());
        if (object.isCore()) {
            data.put("coreState", object.state());
            data.put("moveRequiredTicks", ManualTargeting.CORE_MOVE_REQUIRED_TICKS);
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (ManualTargeting.Action action : availableActions(object, snapshot.state())) {
            Map<String, Object> view = ManualTargeting.toView(action);
            if ("SPAWN".equals(action.type())) {
                List<Map<String, Object>> options = new ArrayList<>();
                for (UnitType type : List.of(UnitType.WORKER, UnitType.VANGUARD, UnitType.RANGER)) {
                    long cost = ProductionPlanner.unitCost(type, snapshot.state().population());
                    boolean enabled = action.enabled() && snapshot.state().resources() >= cost;
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("unitType", type.name());
                    option.put("label", switch (type) {
                        case WORKER -> "工人";
                        case VANGUARD -> "先锋";
                        case RANGER -> "游侠";
                        default -> type.name();
                    });
                    option.put("cost", cost);
                    option.put("enabled", enabled);
                    option.put("note", !action.enabled() ? action.note()
                            : (enabled ? null : "当前资源不足"));
                    options.add(option);
                }
                view.put("options", options);
                view.put("resources", snapshot.state().resources());
            }
            actions.add(view);
        }
        data.put("actions", actions);
        return ApiResponse.ok(data);
    }

    /** 执行一条人工指令 */
    @PostMapping("/{keyId}/action")
    public ResponseEntity<ApiResponse<Map<String, Object>>> execute(HttpServletRequest request,
                                                                    @PathVariable long keyId,
                                                                    @RequestBody ManualActionRequest body) {
        CurrentUser me = current(request);
        if (body == null || body.expectedTick() == null || body.targetId() == null || body.action() == null) {
            throw AllianceException.badRequest("指令不完整");
        }
        if (!actionLimiter.tryAcquire("manual:" + me.id())) {
            throw new AllianceException(429, "操作过于频繁，请稍后再试");
        }
        requireUuid(body.targetId());
        WorldAggregator.MemberSnapshot snapshot = requireControllable(me, keyId);
        if (snapshot.tick() != body.expectedTick()) {
            throw new AllianceException(409, "Tick 已更新，请重新选择动作");
        }
        GameObject object = findOwnObject(snapshot.state(), body.targetId());
        requireWebSafe(object.position());
        if (!(body.action() instanceof ObjectNode action)) {
            throw AllianceException.badRequest("动作格式错误");
        }

        // ① 协议校验：动作形状与兵种能力
        ManualControl.Validation shape = ManualControl.validate(
                object.isCore(), object.unitType(), action);
        if (!shape.ok()) {
            throw AllianceException.badRequest(shape.message());
        }
        // ② 基于最新 state 重算菜单，不信任前端缓存的 enabled/targets。
        String unavailable = unavailableReason(availableActions(object, snapshot.state()), snapshot.state(), action);
        if (unavailable != null) {
            throw new AllianceException(422, unavailable);
        }
        // ③ 联盟红线：不得攻击盟友
        String violation = allianceViolation(snapshot, object, action);
        if (violation != null) {
            throw AllianceException.badRequest(violation);
        }

        GameCommandClient.CommandResult upstream = hostingService.submitManualAction(
                keyId, snapshot.tick(), body.targetId(), object.isCore(), action).join();
        if (!upstream.accepted()) {
            throw upstreamFailure(upstream);
        }
        log.info("[key-{}] 人工接管：{} 执行 {}", keyId, body.targetId(), action.path("type").asText());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tick", snapshot.tick());
        result.put("targetId", body.targetId());
        result.put("action", action.path("type").asText());
        result.put("accepted", true);
        return ResponseEntity.status(202).body(ApiResponse.ok(result));
    }

    private List<ManualTargeting.Action> availableActions(GameObject object, PlayerState state) {
        Set<Cell> enemyCells = new HashSet<>();
        for (WorldAggregator.EnemySighting enemy : aggregator.enemies().values()) {
            if (enemy.pos() != null) enemyCells.add(enemy.pos());
        }
        return ManualTargeting.availableActions(object, state,
                cell -> aggregator.obstacles().containsKey(cell),
                cell -> aggregator.resources().containsKey(cell),
                aggregator.memberIndex(), enemyCells::contains);
    }

    private static String unavailableReason(List<ManualTargeting.Action> available,
                                            PlayerState state, ObjectNode submitted) {
        String type = submitted.path("type").asText("");
        ManualTargeting.Action action = available.stream().filter(a -> type.equals(a.type()))
                .findFirst().orElse(null);
        if (action == null) return "当前对象不支持动作 " + type;
        if (!action.enabled()) return action.note() == null || action.note().isBlank() ? "当前无法执行该动作" : action.note();
        if ("SPAWN".equals(type)) {
            UnitType unitType = UnitType.fromName(submitted.path("unit_type").asText());
            if (unitType == null || unitType == UnitType.CORE) return "生产类型不合法";
            long cost = ProductionPlanner.unitCost(unitType, state.population());
            return state.resources() >= cost ? null : "当前资源不足，预估需要 " + cost;
        }
        if (!action.needsTarget()) return null;
        ManualTargeting.Target target = null;
        if ("SHOOT".equals(type)) {
            JsonNode cell = submitted.get("expected_cell");
            long x = cell.get(0).longValue();
            long y = cell.get(1).longValue();
            target = action.targets().stream().filter(t -> t.pos().x() == x && t.pos().y() == y)
                    .findFirst().orElse(null);
        } else {
            String direction = submitted.path("direction").asText("");
            target = action.targets().stream().filter(t -> direction.equals(t.direction())).findFirst().orElse(null);
        }
        if (target == null) return "目标格不在当前动作范围内";
        if (target.ally()) return target.note() == null ? "联盟规则禁止攻击盟友" : target.note();
        return target.blocked() ? (target.note() == null ? "目标格不可选" : target.note()) : null;
    }

    private static AllianceException upstreamFailure(GameCommandClient.CommandResult result) {
        int status = switch (result.status()) {
            case 409, 422, 429, 504 -> result.status();
            default -> 502;
        };
        String message = switch (result.status()) {
            case 409 -> "Tick 已过期，请基于新状态重新下达";
            case 422 -> "游戏服务器拒绝了计划";
            case 429 -> "本 Tick 提交过于频繁，请等待下一 Tick";
            case 504 -> "提交结果未知，请等待回执或下一 Tick，不要立即重复提交";
            default -> "游戏命令服务暂时不可用";
        };
        return new AllianceException(status, message);
    }

    /**
     * 用与自动拦截同一套判定（ThreatMonitor）复核人工指令，
     * 保证手动操作与联盟保护规则一致：命中盟友直接拒绝。
     */
    private String allianceViolation(WorldAggregator.MemberSnapshot snapshot,
                                     GameObject object, ObjectNode action) {
        if (object.isCore()) {
            return null; // Core 动作不含攻击
        }
        ObjectNode plan = GameJson.MAPPER.createObjectNode();
        plan.put("tick", snapshot.tick());
        ObjectNode unitActions = GameJson.MAPPER.createObjectNode();
        unitActions.set(object.id(), action);
        plan.set("unit_actions", unitActions);

        Map<String, Cell> positions = new HashMap<>();
        if (snapshot.state().objects() != null) {
            for (GameObject o : snapshot.state().objects()) {
                if (o.isControlled() && o.id() != null && o.position() != null) {
                    positions.put(o.id(), o.position());
                }
            }
        }
        List<ThreatMonitor.Offense> offenses =
                ThreatMonitor.inspect(snapshot.userId(), plan, positions, aggregator.memberIndex());
        if (offenses.isEmpty()) {
            return null;
        }
        ThreatMonitor.Offense first = offenses.get(0);
        String victim = first.victim().gameUsername() == null
                ? ("成员#" + first.victim().userId()) : ("@" + first.victim().gameUsername());
        return "联盟规则禁止攻击盟友：该动作会命中 " + victim
                + (first.targetCell() == null ? "" : (" 所在的 " + first.targetCell()));
    }

    private WorldAggregator.MemberSnapshot requireControllable(CurrentUser me, long keyId) {
        WorldAggregator.MemberSnapshot snapshot = aggregator.members().get(keyId);
        if (snapshot == null || snapshot.state() == null) {
            throw AllianceException.notFound("该账号暂无游戏状态，请稍后再试");
        }
        if (snapshot.userId() != me.id()) {
            throw AllianceException.forbidden("只能操控自己的账号");
        }
        HostingStatus status = hostingService.statusOf(keyId);
        if (status != HostingStatus.RUNNING) {
            String message = switch (status) {
                case PAUSED_CONFLICT -> "托管冲突暂停中，恢复后才能手动操控";
                case WAITING_STATE -> "游戏连接已建立，正在等待新状态";
                case DISCONNECTED -> "游戏连接已断开，重连后才能操作";
                default -> "请先为该账号开启联盟托管";
            };
            throw AllianceException.forbidden(message);
        }
        return snapshot;
    }

    private static GameObject findOwnObject(PlayerState state, String objectId) {
        requireUuid(objectId);
        if (state.objects() != null) {
            for (GameObject o : state.objects()) {
                if (o.isControlled() && objectId.equals(o.id())) {
                    return o;
                }
            }
        }
        throw AllianceException.notFound("对象不存在或已阵亡");
    }

    private static void requireUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.equals(new UUID(0, 0)) || !parsed.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
        } catch (RuntimeException e) {
            throw AllianceException.badRequest("对象 ID 必须是非零、规范小写 UUID");
        }
    }

    private static void requireWebSafe(Cell cell) {
        if (!GridMath.isWebSafe(cell)) {
            throw new AllianceException(422,
                    "该对象坐标超出浏览器安全整数范围，不能使用网页手动操控");
        }
    }

    private static CurrentUser current(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute(CurrentUser.ATTR);
    }
}
