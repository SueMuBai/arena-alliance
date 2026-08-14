package com.arena.alliance.rules;

import com.arena.alliance.game.GameJson;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);
    private static final String RULES_KEY = "rules";

    private final SettingRepository repository;
    private volatile RuleSettings cached = new RuleSettings();

    public RuleService(SettingRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void load() {
        repository.findById(RULES_KEY).ifPresent(row -> {
            try {
                cached = GameJson.MAPPER.readValue(row.getValue(), RuleSettings.class);
            } catch (Exception e) {
                log.warn("规则配置解析失败，使用默认规则: {}", e.getMessage());
            }
        });
    }

    public RuleSettings rules() {
        return cached;
    }

    /** 合并式更新：只覆盖 patch 中出现的字段 */
    public synchronized RuleSettings update(JsonNode patch) {
        try {
            RuleSettings base = GameJson.MAPPER.treeToValue(GameJson.MAPPER.valueToTree(cached), RuleSettings.class);
            RuleSettings merged = GameJson.MAPPER.readerForUpdating(base).readValue(patch);
            String json = GameJson.MAPPER.writeValueAsString(merged);
            SettingEntity row = repository.findById(RULES_KEY).orElse(new SettingEntity(RULES_KEY, json));
            row.setValue(json);
            repository.save(row);
            cached = merged;
            return merged;
        } catch (Exception e) {
            throw new IllegalArgumentException("规则格式错误: " + e.getMessage(), e);
        }
    }
}
