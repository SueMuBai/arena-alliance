package com.arena.alliance.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 可见格覆盖的启动预载与批量落库。 */
@Component
public class ExplorationStore {

    private static final Logger log = LoggerFactory.getLogger(ExplorationStore.class);
    private final ExploredChunkRepository repository;
    private final WorldAggregator aggregator;

    public ExplorationStore(ExploredChunkRepository repository, WorldAggregator aggregator) {
        this.repository = repository;
        this.aggregator = aggregator;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void preload() {
        int loaded = 0;
        for (ExploredChunk chunk : repository.findAll()) {
            try {
                aggregator.preloadCoverage(chunk.coord(), Base64.getDecoder().decode(chunk.getCoverageBase64()),
                        chunk.getUpdatedTick());
                loaded++;
            } catch (IllegalArgumentException e) {
                log.warn("忽略损坏的探索位图 {}: {}", chunk.coord(), e.getMessage());
            }
        }
        if (loaded > 0) log.info("已恢复探索覆盖 {} 个 Chunk", loaded);
    }

    @Scheduled(fixedDelay = 10_000)
    public void flush() {
        List<WorldAggregator.CoverageUpdate> pending = aggregator.drainPendingCoverage(500);
        if (pending.isEmpty()) return;
        try {
            List<ExploredChunk> entities = new ArrayList<>(pending.size());
            for (WorldAggregator.CoverageUpdate update : pending) {
                entities.add(new ExploredChunk(update.coord(),
                        Base64.getEncoder().encodeToString(update.bytes()), update.updatedTick()));
            }
            repository.saveAll(entities);
        } catch (Exception e) {
            aggregator.requeueCoverage(pending);
            log.warn("探索覆盖落库失败，已重新入队: {}", e.getMessage());
        }
    }
}
