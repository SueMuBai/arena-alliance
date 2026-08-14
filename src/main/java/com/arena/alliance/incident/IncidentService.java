package com.arena.alliance.incident;

import com.arena.alliance.map.MapSseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件审计：落库 + SSE 实时推送到地图页事件流。
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository repository;
    private final MapSseService sse;

    public IncidentService(IncidentRepository repository, MapSseService sse) {
        this.repository = repository;
        this.sse = sse;
    }

    public Incident record(IncidentType type, long tick,
                           Long attackerUserId, String attackerName,
                           Long victimUserId, String victimName,
                           String detail) {
        Incident incident = new Incident();
        incident.setType(type.name());
        incident.setTick(tick);
        incident.setAttackerUserId(attackerUserId);
        incident.setAttackerName(attackerName);
        incident.setVictimUserId(victimUserId);
        incident.setVictimName(victimName);
        incident.setDetail(detail);
        try {
            incident = repository.save(incident);
        } catch (Exception e) {
            log.warn("事件落库失败: {}", e.getMessage());
        }
        sse.broadcastIncident(toMap(incident));
        log.info("[事件] {} tick={} attacker={} victim={} {}", type, tick, attackerName, victimName,
                detail == null ? "" : detail);
        return incident;
    }

    public List<Map<String, Object>> recent(int limit) {
        List<Incident> rows = limit <= 50
                ? repository.findTop50ByOrderByIdDesc()
                : repository.findTop200ByOrderByIdDesc();
        return rows.stream().map(IncidentService::toMap).toList();
    }

    public static Map<String, Object> toMap(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("type", i.getType());
        m.put("tick", i.getTick());
        m.put("attackerUserId", i.getAttackerUserId());
        m.put("attackerName", i.getAttackerName());
        m.put("victimUserId", i.getVictimUserId());
        m.put("victimName", i.getVictimName());
        m.put("detail", i.getDetail());
        m.put("createdAt", i.getCreatedAt() == null ? null : i.getCreatedAt().toString());
        return m;
    }
}
