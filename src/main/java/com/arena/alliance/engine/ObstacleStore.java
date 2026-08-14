package com.arena.alliance.engine;

import com.arena.alliance.game.dto.Cell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 障碍记忆的持久化：启动时预载，运行中批量落库。
 */
@Component
public class ObstacleStore {

    private static final Logger log = LoggerFactory.getLogger(ObstacleStore.class);

    private final WorldCellRepository repository;
    private final WorldAggregator aggregator;

    public ObstacleStore(WorldCellRepository repository, WorldAggregator aggregator) {
        this.repository = repository;
        this.aggregator = aggregator;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void preload() {
        List<WorldCell> cells = repository.findAll();
        if (!cells.isEmpty()) {
            aggregator.preloadObstacles(cells.stream().map(c -> new Cell(c.getX(), c.getY())).toList());
            log.info("已恢复障碍记忆 {} 格", cells.size());
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void flush() {
        List<Cell> pending = aggregator.drainPendingObstacles(2000);
        if (pending.isEmpty()) {
            return;
        }
        try {
            repository.saveAll(pending.stream().map(c -> new WorldCell(c.x(), c.y())).toList());
        } catch (Exception e) {
            log.warn("障碍记忆落库失败: {}", e.getMessage());
        }
    }
}
