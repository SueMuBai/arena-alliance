package com.arena.alliance.hosting;

/**
 * 托管运行态（不落库；DB 的 hostingEnabled 是成员意愿，这里是实际状态）。
 */
public enum HostingStatus {
    /** WebSocket 初次连接或重连后，正在等待新的完整 state */
    WAITING_STATE,
    /** 指挥官正在指挥该账号 */
    RUNNING,
    /** WebSocket 已断开，该账号不参与规划对齐 */
    DISCONNECTED,
    /** 检测到成员自己的 agent 在运行，冲突暂停（成员手动恢复） */
    PAUSED_CONFLICT,
    /** 未开启 / key 停用 / 管理员总闸关闭 */
    OFF
}
