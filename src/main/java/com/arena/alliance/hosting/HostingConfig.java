package com.arena.alliance.hosting;

import com.arena.alliance.game.GameJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 托管配置 = 模式预设 + 高级覆盖。阵容默认对齐参考 agent（23W+3V+4R，总人口 30）。
 */
public record HostingConfig(
        PilotMode mode,
        int workerTarget,
        int vanguardTarget,
        int rangerTarget
) {

    public static final HostingConfig DEFAULT = of(PilotMode.BALANCED);

    /** 模式预设（ModePreset）：mode → 完整参数集；后续里程碑在此追加差异参数 */
    public static HostingConfig of(PilotMode mode) {
        return new HostingConfig(mode, 23, 3, 4);
    }

    /** 容错解析：null/坏 JSON → 默认；mode 之外的字段按预设补齐后接受覆盖 */
    public static HostingConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT;
        }
        try {
            JsonNode node = GameJson.MAPPER.readTree(json);
            HostingConfig preset = of(PilotMode.fromName(node.path("mode").asText(null)));
            return new HostingConfig(
                    preset.mode(),
                    boundedInt(node, "workerTarget", preset.workerTarget()),
                    boundedInt(node, "vanguardTarget", preset.vanguardTarget()),
                    boundedInt(node, "rangerTarget", preset.rangerTarget()));
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    private static int boundedInt(JsonNode node, String field, int def) {
        int value = node.path(field).asInt(def);
        return Math.max(0, Math.min(30, value));
    }

    public String toJson() {
        ObjectNode node = GameJson.MAPPER.createObjectNode();
        node.put("mode", mode.name());
        node.put("workerTarget", workerTarget);
        node.put("vanguardTarget", vanguardTarget);
        node.put("rangerTarget", rangerTarget);
        return node.toString();
    }
}
