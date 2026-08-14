package com.arena.alliance.engine;

/**
 * 引擎与领域层解耦用的应用事件。
 */
public final class EngineEvents {

    private EngineEvents() {
    }

    /** key 新增或重新启用 → 引擎应启动会话 */
    public record KeyActivated(long keyId) {
    }

    /** key 停用或删除 → 引擎应停止会话 */
    public record KeyDeactivated(long keyId) {
    }

    /** key 的地图/名册隐私设置变化 → 地图快照应尽快刷新 */
    public record KeyPrivacyChanged(long keyId) {
    }

    /** 成员被踢出联盟 → 引擎停止其全部会话 */
    public record MemberKicked(long userId, String reason) {
    }
}
