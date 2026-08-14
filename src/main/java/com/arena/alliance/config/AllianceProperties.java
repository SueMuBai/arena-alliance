package com.arena.alliance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台全局配置。游戏 API 地址可整体替换（自建服/测试服），
 * WebSocket 地址由 api-base 推导：https -> wss。
 */
@ConfigurationProperties(prefix = "alliance")
public record AllianceProperties(
        String dataDir,
        String secret,
        int sessionTtlHours,
        boolean devLoginEnabled,
        Game game,
        LinuxDo linuxdo
) {

    public record Game(String apiBase, String wsPath, String commandsPath, String proxy) {

        public String wsUrl() {
            return apiBase.replaceFirst("^http", "ws") + wsPath;
        }

        public String commandsUrl() {
            return apiBase + commandsPath;
        }
    }

    public record LinuxDo(
            String clientId,
            String clientSecret,
            String authorizeUrl,
            String tokenUrl,
            String userUrl,
            String redirectBase,
            String proxy
    ) {
        public boolean configured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
