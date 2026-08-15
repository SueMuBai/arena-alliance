package com.arena.alliance.hosting.plan;

import com.arena.alliance.game.dto.Cell;
import com.arena.alliance.game.dto.GridMath;
import com.arena.alliance.engine.ChunkCoord;
import com.arena.alliance.hosting.PilotMode;
import com.arena.alliance.hosting.plan.PlanningModels.AccountView;
import com.arena.alliance.hosting.plan.PlanningModels.PlanningInput;
import com.arena.alliance.hosting.plan.PlanningModels.ScoutMission;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 侦察条令（联盟级扩图分工）：
 * 以 32×32 chunk 为扇区单位，从联盟共享记忆统计已探明区域，
 * 把 Core 周边未探明扇区按"最近且未被认领"的原则分派给各账号的空闲 Worker，
 * 保证同一扇区同一时刻只有一名侦察兵（跨账号不重叠）。
 *
 * 编制：普通模式每账号 1 名侦察兵，🔭SCOUT 模式加倍为 2 名；
 * 🛡TURTLE 不外派侦察。
 */
public final class ScoutDoctrine {

    /** 扇区搜索半径（以 Core 所在 chunk 为中心的切比雪夫半径） */
    static final int SEARCH_CHUNK_RADIUS = 3;
    /** 普通模式侦察编制 */
    static final int SCOUTS_DEFAULT = 1;
    /** SCOUT 模式侦察编制（加倍） */
    static final int SCOUTS_SCOUT_MODE = 2;
    /** 侦察任务租约：到期或扇区已探明才换目标，防止每 Tick 抖动 */
    static final int MISSION_LEASE_TICKS = 60;

    private ScoutDoctrine() {
    }

    /**
     * 分派侦察任务。
     *
     * @param idleWorkers 未被经济任务占用的空载 Worker（keyId → 单位 ID → 位置）
     * @return unitId → 任务（含续期后的任务）
     */
    public static Map<String, ScoutMission> allocate(PlanningInput in,
                                                     Map<Long, Map<String, Cell>> idleWorkers,
                                                     List<String> journal) {
        Map<String, ScoutMission> missions = new LinkedHashMap<>();
        Set<ChunkCoord> claimedChunks = new HashSet<>();

        // ① 续约：单位仍空闲、扇区仍未探明、租约未到期
        for (AccountView account : in.accounts()) {
            if (account.config().mode() == PilotMode.TURTLE) {
                continue; // TURTLE 不仅不新派，也不续约历史侦察任务
            }
            Map<String, Cell> idle = idleWorkers.getOrDefault(account.keyId(), Map.of());
            for (Map.Entry<String, Cell> e : idle.entrySet()) {
                ScoutMission prior = in.priorScouts().get(e.getKey());
                if (prior == null || prior.keyId() != account.keyId()) {
                    continue;
                }
                ChunkCoord chunk = PlanningInput.chunkKey(prior.chunkX(), prior.chunkY());
                boolean expired = in.tick() - prior.sinceTick() > MISSION_LEASE_TICKS;
                if (!expired && !in.explored(chunk) && claimedChunks.add(chunk)) {
                    missions.put(e.getKey(), prior);
                }
            }
        }

        // ② 新派：按编制补齐，每次选离该账号 Core 最近的未探明未认领扇区
        record Candidate(long dist, long cx, long cy, String unitId, long keyId) {
        }
        for (AccountView account : in.accounts()) {
            PilotMode mode = account.config().mode();
            if (mode == PilotMode.TURTLE) {
                continue;
            }
            int quota = mode == PilotMode.SCOUT ? SCOUTS_SCOUT_MODE : SCOUTS_DEFAULT;
            Map<String, Cell> idle = idleWorkers.getOrDefault(account.keyId(), Map.of());
            long assigned = missions.values().stream().filter(m -> m.keyId() == account.keyId()).count();
            if (assigned >= quota || idle.isEmpty()) {
                continue;
            }
            Cell corePos = account.state() == null || account.state().controlledCore() == null
                    ? null : account.state().controlledCore().position();
            if (corePos == null) {
                continue;
            }
            long baseCx = PlanningInput.chunkOf(corePos.x());
            long baseCy = PlanningInput.chunkOf(corePos.y());

            List<Candidate> candidates = new ArrayList<>();
            for (long cx = baseCx - SEARCH_CHUNK_RADIUS; cx <= baseCx + SEARCH_CHUNK_RADIUS; cx++) {
                for (long cy = baseCy - SEARCH_CHUNK_RADIUS; cy <= baseCy + SEARCH_CHUNK_RADIUS; cy++) {
                    ChunkCoord chunk = PlanningInput.chunkKey(cx, cy);
                    if (in.explored(chunk) || claimedChunks.contains(chunk)) {
                        continue;
                    }
                    Cell center = chunk.cell(16 * ChunkCoord.SIZE + 16).orElse(null);
                    if (center == null) continue;
                    for (Map.Entry<String, Cell> e : idle.entrySet()) {
                        if (missions.containsKey(e.getKey())) {
                            continue;
                        }
                        candidates.add(new Candidate(manhattan(e.getValue(), center),
                                cx, cy, e.getKey(), account.keyId()));
                    }
                }
            }
            candidates.sort(Comparator.comparingLong(Candidate::dist)
                    .thenComparingLong(Candidate::cx)
                    .thenComparingLong(Candidate::cy)
                    .thenComparing(Candidate::unitId));
            for (Candidate c : candidates) {
                if (assigned >= quota) {
                    break;
                }
                if (missions.containsKey(c.unitId())) {
                    continue;
                }
                ChunkCoord chunk = PlanningInput.chunkKey(c.cx(), c.cy());
                if (!claimedChunks.add(chunk)) {
                    continue;
                }
                missions.put(c.unitId(), new ScoutMission(c.keyId(), c.unitId(), c.cx(), c.cy(), in.tick()));
                assigned++;
                journal.add("tick=" + in.tick() + " 侦察：key=" + c.keyId()
                        + " 单位=" + c.unitId() + " → 扇区[" + c.cx() + "," + c.cy() + "]");
            }
        }
        return missions;
    }

    /** 迁移/测试辅助：已知格只计入对应位，不再把整个 Chunk 误判为已探明。 */
    public static Map<ChunkCoord, BitSet> exploredChunks(Set<Cell> obstacles, Set<Cell> resources) {
        Map<ChunkCoord, BitSet> chunks = new LinkedHashMap<>();
        java.util.stream.Stream.concat(obstacles.stream(), resources.stream()).forEach(cell -> {
            ChunkCoord coord = ChunkCoord.of(cell);
            chunks.computeIfAbsent(coord, ignored -> new BitSet(ChunkCoord.CELL_COUNT))
                    .set(ChunkCoord.localIndex(cell));
        });
        return chunks;
    }

    /** 选择目标 Chunk 内距离 Worker 最近的未观察、非已知障碍格。 */
    public static Cell frontierTarget(ScoutMission mission, Cell from,
                                      Map<ChunkCoord, BitSet> coverage, Set<Cell> obstacles) {
        ChunkCoord coord = new ChunkCoord(mission.chunkX(), mission.chunkY());
        BitSet seen = coverage.getOrDefault(coord, new BitSet(ChunkCoord.CELL_COUNT));
        Cell best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < ChunkCoord.CELL_COUNT; index++) {
            if (seen.get(index)) continue;
            Cell cell = coord.cell(index).orElse(null);
            if (cell == null || obstacles.contains(cell)) continue;
            long distance = GridMath.manhattan(from, cell);
            if (distance < bestDistance || (distance == bestDistance
                    && (best == null || cell.x() < best.x() || (cell.x() == best.x() && cell.y() < best.y())))) {
                best = cell;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static long manhattan(Cell a, Cell b) {
        return GridMath.manhattan(a, b);
    }
}
