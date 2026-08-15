package com.arena.alliance.hosting;

import com.arena.alliance.engine.WorldAggregator;
import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GameObject;
import com.arena.alliance.game.dto.GridMath;
import com.arena.alliance.game.dto.PlayerState;
import com.arena.alliance.game.dto.UnitType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 手动接管的可执行动作与合法目标格计算（纯函数，前端据此高亮）。
 *
 * 规则来源为官方文档：
 * - MOVE：四个正交相邻格，障碍不可入；
 * - SWEEP：四个正交相邻格（打空格也合法，但会白打）；
 * - SHOOT：八方向（正交 + 严格对角）1-3 格，中途障碍挡住弹道；
 * - START_MOVE：Core 迁移四个正交相邻格，资源地形与障碍都不可入，
 *   且迁移需要 4 个 Tick，期间 Core 只能 WAIT / CANCEL_MOVE / SELF_DESTRUCT。
 *
 * 命中盟友的格子会被标记 ally=true（联盟红线，前端置灰并提示）。
 */
public final class ManualTargeting {

    /** 目标格：blocked 表示不可选，ally 表示会打到盟友（红线） */
    public record Target(Cell pos, String direction, boolean blocked, boolean ally, boolean enemy, String note) {
    }

    /** 一个可执行动作；needsTarget 为真时前端进入选格模式 */
    public record Action(String type, String label, boolean enabled, boolean needsTarget,
                         boolean dangerous, String note, List<Target> targets) {
    }

    /** Core 迁移进度（官方：需要 4 个 Tick） */
    public static final int CORE_MOVE_REQUIRED_TICKS = 4;

    private static final String[] DIRECTIONS = {"UP", "DOWN", "LEFT", "RIGHT"};
    private static final long[][] ORTHOGONAL = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    private static final long[][] EIGHT_WAY = {
            {0, -1}, {0, 1}, {-1, 0}, {1, 0}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private ManualTargeting() {
    }

    /**
     * 列出该对象当前可执行的动作。
     *
     * <p>障碍/资源用 {@link Predicate} 传入而非集合拷贝：联盟共享记忆可能有十万级格子，
     * 每次开面板整份拷贝会让请求卡住。这里只需查询二十余个候选格。
     *
     * @param object     被操作的对象（自己的 Core 或单位）
     * @param state      该账号最新完整状态
     * @param isObstacle 是否为障碍地形
     * @param isResource 是否为资源地形（Core 不能迁入）
     * @param allyIndex  全联盟对象索引（用于红线标记）
     * @param isEnemy    是否为敌方占据格
     */
    public static List<Action> availableActions(GameObject object, PlayerState state,
                                                Predicate<Cell> isObstacle, Predicate<Cell> isResource,
                                                WorldAggregator.MemberIndex allyIndex,
                                                Predicate<Cell> isEnemy) {
        List<Action> actions = new ArrayList<>();
        Cell pos = object.position();
        if (pos == null) {
            return actions;
        }
        if (object.isCore()) {
            boolean moving = "MOVING".equals(object.state());
            if (moving) {
                // 迁移中：官方规定其他 Core 动作一律失败（CORE_ALREADY_MOVING）
                actions.add(simple("CANCEL_MOVE", "取消迁移", true, false, "迁移进度将清零"));
                actions.add(simple("WAIT", "继续迁移", true, false, "保持迁移不变"));
                actions.add(disabled("SPAWN", "生产单位", "迁移中无法生产"));
                actions.add(disabled("HEAL", "治疗核心", "迁移中无法治疗"));
                actions.add(disabled("REPAIR_SHIELD", "修复护盾", "迁移中无法修盾"));
                actions.add(disabled("START_MOVE", "开始迁移", "已在迁移中"));
                actions.add(new Action("SELF_DESTRUCT", "自毁核心", true, false, true,
                        "迁移中仍可自毁，后果不可逆", List.of()));
                return actions;
            }
            long resourcesHeld = state.resources();
            boolean spawnCellFull = occupyingCount(state, pos) >= 2;
            actions.add(new Action("SPAWN", "生产单位", !spawnCellFull, false, false,
                    spawnCellFull ? "Core 所在格已达 2 个实体上限"
                            : "Worker/Vanguard/Ranger，价格随人口上涨", List.of()));
            GameObject core = object;
            actions.add(simple("HEAL", "治疗核心",
                    core.hp() != null && core.hp() < UnitType.CORE.maxHp() && resourcesHeld > 0,
                    false, "1 资源恢复 1 HP"));
            actions.add(simple("REPAIR_SHIELD", "修复护盾",
                    core.shield() != null && core.shield() < coreShieldCap(state) && resourcesHeld > 0,
                    false, "1 资源恢复 1 护盾"));
            actions.add(new Action("START_MOVE", "开始迁移", true, true, false,
                    "需要 " + CORE_MOVE_REQUIRED_TICKS + " 个 Tick 才能完成，期间无法生产/治疗/接收上缴",
                    coreMoveTargets(object, state, isObstacle, isResource, allyIndex, isEnemy)));
            actions.add(beaconAction(pos, state, "PICKUP_BEACON", "拾取信标"));
            actions.add(beaconAction(pos, state, "DROP_BEACON", "放下信标"));
            actions.add(new Action("SELF_DESTRUCT", "自毁核心", true, false, true,
                    "将摧毁核心、库存与全部单位，后果不可逆", List.of()));
            return actions;
        }

        UnitType type = UnitType.fromName(object.unitType());
        Cell corePos = state.controlledCore() == null ? null : state.controlledCore().position();
        boolean coreMoving = state.controlledCore() != null && "MOVING".equals(state.controlledCore().state());
        int cargo = object.cargo() == null ? 0 : object.cargo();

        actions.add(new Action("MOVE", "移动", true, true, false, "移动到相邻格",
                moveTargets(object, state, isObstacle, allyIndex, isEnemy)));

        if (type == UnitType.WORKER) {
            actions.add(simple("HARVEST", "采集资源",
                    isResource.test(pos) && cargo == 0, false,
                    cargo > 0 ? "已满载，需先上缴" : (isResource.test(pos) ? "" : "当前格没有资源")));
            boolean canDeposit = cargo > 0 && pos.equals(corePos) && !coreMoving;
            actions.add(simple("DEPOSIT", "上缴资源", canDeposit, false,
                    coreMoving ? "核心迁移中无法接收上缴"
                            : (cargo == 0 ? "没有携带资源" : (pos.equals(corePos) ? "" : "需要站在自己核心上"))));
        }
        if (type == UnitType.VANGUARD) {
            actions.add(new Action("SWEEP", "扫击", true, true, false,
                    "对相邻格所有敌方单位造成 1 点伤害",
                sweepTargets(pos, allyIndex, isEnemy)));
        }
        if (type == UnitType.RANGER) {
            actions.add(new Action("SHOOT", "射击", true, true, false,
                    "八方向 1-3 格，障碍会挡住弹道",
                    shootTargets(pos, isObstacle, allyIndex, isEnemy)));
        }

        int maxHp = type == null ? 0 : type.maxHp();
        boolean canHeal = object.hp() != null && object.hp() < maxHp
                && pos.equals(corePos) && !coreMoving && state.resources() > 0;
        actions.add(simple("HEAL", "治疗", canHeal, false,
                coreMoving ? "核心迁移中无法治疗"
                        : (pos.equals(corePos) ? "" : "需要站在自己核心上")));
        actions.add(beaconAction(pos, state, "PICKUP_BEACON", "拾取信标"));
        actions.add(beaconAction(pos, state, "DROP_BEACON", "放下信标"));
        actions.add(simple("WAIT", "待命", true, false, "本 Tick 不行动"));
        actions.add(new Action("SELF_DESTRUCT", "自毁单位", true, false, true,
                "移除该单位，携带的资源会掉落", List.of()));
        return actions;
    }

    private static List<Target> moveTargets(GameObject object, PlayerState state, Predicate<Cell> isObstacle,
                                            WorldAggregator.MemberIndex allyIndex, Predicate<Cell> isEnemy) {
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < ORTHOGONAL.length; i++) {
            long[] d = ORTHOGONAL[i];
            Cell cell = GridMath.offset(object.position(), d[0], d[1]).orElse(null);
            if (cell == null || !GridMath.isWebSafe(cell)) continue;
            boolean obstacle = isObstacle.test(cell);
            boolean enemy = isEnemy.test(cell);
            boolean foreignAlly = isForeignAlly(cell, object, allyIndex);
            boolean full = occupyingCount(state, cell) >= 2;
            String note = obstacle ? "障碍地形" : (enemy ? "敌方占据，无法进入"
                    : (foreignAlly ? "其他盟友占据，无法进入" : (full ? "目标格已达 2 个实体上限" : null)));
            targets.add(new Target(cell, DIRECTIONS[i], obstacle || enemy || foreignAlly || full,
                    foreignAlly, enemy, note));
        }
        return targets;
    }

    private static List<Target> coreMoveTargets(GameObject object, PlayerState state,
                                                Predicate<Cell> isObstacle, Predicate<Cell> isResource,
                                                WorldAggregator.MemberIndex allyIndex, Predicate<Cell> isEnemy) {
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < ORTHOGONAL.length; i++) {
            long[] d = ORTHOGONAL[i];
            Cell cell = GridMath.offset(object.position(), d[0], d[1]).orElse(null);
            if (cell == null || !GridMath.isWebSafe(cell)) continue;
            boolean obstacle = isObstacle.test(cell);
            boolean resource = isResource.test(cell);
            boolean enemy = isEnemy.test(cell);
            boolean foreignAlly = isForeignAlly(cell, object, allyIndex);
            boolean full = occupyingCount(state, cell) >= 2;
            String note = obstacle ? "障碍地形" : (resource ? "资源地形，核心无法进入"
                    : (enemy ? "敌方占据" : (foreignAlly ? "其他盟友占据"
                    : (full ? "目标格已达 2 个实体上限" : null))));
            targets.add(new Target(cell, DIRECTIONS[i], obstacle || resource || enemy || foreignAlly || full,
                    foreignAlly, enemy, note));
        }
        return targets;
    }

    private static List<Target> sweepTargets(Cell from,
                                             WorldAggregator.MemberIndex allyIndex, Predicate<Cell> isEnemy) {
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < ORTHOGONAL.length; i++) {
            long[] d = ORTHOGONAL[i];
            Cell cell = GridMath.offset(from, d[0], d[1]).orElse(null);
            if (cell == null || !GridMath.isWebSafe(cell)) continue;
            boolean ally = allyIndex.byCell().containsKey(cell);
            targets.add(new Target(cell, DIRECTIONS[i], ally, ally, isEnemy.test(cell),
                    ally ? "盟友所在格，联盟规则禁止攻击" : null));
        }
        return targets;
    }

    private static List<Target> shootTargets(Cell from, Predicate<Cell> isObstacle,
                                             WorldAggregator.MemberIndex allyIndex, Predicate<Cell> isEnemy) {
        List<Target> targets = new ArrayList<>();
        for (long[] d : EIGHT_WAY) {
            for (int range = 1; range <= 3; range++) {
                Cell cell = GridMath.offset(from, d[0] * range, d[1] * range).orElse(null);
                if (cell == null || !GridMath.isWebSafe(cell)) break;
                // 中间格有障碍则该方向后续都打不到
                boolean blockedByObstacle = false;
                for (int step = 1; step < range; step++) {
                    Cell intermediate = GridMath.offset(from, d[0] * step, d[1] * step).orElse(null);
                    if (intermediate == null || isObstacle.test(intermediate)) {
                        blockedByObstacle = true;
                        break;
                    }
                }
                if (blockedByObstacle) {
                    break;
                }
                boolean ally = allyIndex.byCell().containsKey(cell);
                targets.add(new Target(cell, null, ally, ally, isEnemy.test(cell),
                        ally ? "盟友所在格，联盟规则禁止攻击" : null));
            }
        }
        return targets;
    }

    private static Action beaconAction(Cell pos, PlayerState state, String type, String label) {
        PlayerState.Beacon beacon = state.beacon();
        boolean onBeaconCell = beacon != null && beacon.position() != null && beacon.position().equals(pos);
        if ("PICKUP_BEACON".equals(type)) {
            return simple(type, label, onBeaconCell, false,
                    onBeaconCell ? "" : "信标不在当前格");
        }
        return simple(type, label, true, false, "仅当前携带者可放下");
    }

    private static Action simple(String type, String label, boolean enabled, boolean needsTarget, String note) {
        return new Action(type, label, enabled, needsTarget, false, note, List.of());
    }

    private static Action disabled(String type, String label, String note) {
        return new Action(type, label, false, false, false, note, List.of());
    }

    public static int coreShieldCap(PlayerState state) {
        if (state == null || state.beacon() == null || state.beacon().carrierId() == null
                || state.objects() == null) return 5;
        String carrier = state.beacon().carrierId();
        return state.objects().stream().anyMatch(o -> o.isControlled() && carrier.equals(o.id())) ? 10 : 5;
    }

    private static int occupyingCount(PlayerState state, Cell cell) {
        if (state == null || state.objects() == null || cell == null) return 0;
        int count = 0;
        for (GameObject o : state.objects()) {
            if ((o.isCore() || o.isUnit()) && cell.equals(o.position())) count++;
        }
        return count;
    }

    private static boolean isForeignAlly(Cell cell, GameObject actor,
                                         WorldAggregator.MemberIndex allyIndex) {
        WorldAggregator.OwnerRef owner = allyIndex.byCell().get(cell);
        if (owner == null) return false;
        WorldAggregator.OwnerRef actorOwner = actor.id() == null ? null : allyIndex.byId().get(actor.id());
        if (actorOwner != null) return owner.keyId() != actorOwner.keyId();
        return actor.ownerUsername() == null || owner.gameUsername() == null
                || !owner.gameUsername().equals(actor.ownerUsername());
    }

    /** 序列化为前端可直接消费的结构 */
    public static Map<String, Object> toView(Action action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", action.type());
        m.put("label", action.label());
        m.put("enabled", action.enabled());
        m.put("needsTarget", action.needsTarget());
        m.put("dangerous", action.dangerous());
        m.put("note", action.note());
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Target t : action.targets()) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("pos", new long[]{t.pos().x(), t.pos().y()});
            tm.put("direction", t.direction());
            tm.put("blocked", t.blocked());
            tm.put("ally", t.ally());
            tm.put("enemy", t.enemy());
            tm.put("note", t.note());
            targets.add(tm);
        }
        m.put("targets", targets);
        return m;
    }
}
