package com.arena.alliance.hosting;

/**
 * 托管角色偏好（模式）：决定该账号在联盟分工（TaskAllocator）中的任务权重与策略参数。
 * 新增模式只需加一个枚举值并在 HostingConfig.of 补一行预设。
 */
public enum PilotMode {

    /** ⚔ 我为联盟扫清障碍：优先接清野/打击任务（M4 生效），反击积极 */
    CLEAR("⚔", "我为联盟扫清障碍"),

    /** 🛡 别浪，等我发育起来：只接经济/留守任务，预撤阈值放宽（M2 生效） */
    TURTLE("🛡", "别浪，等我发育起来"),

    /** 🔭 我愿为联盟探明一切：优先接侦察扇区，侦察编制加倍（M3 生效） */
    SCOUT("🔭", "我愿为联盟探明一切"),

    /** ⚖ 稳健运营（默认）：参考 agent 的均衡策略 */
    BALANCED("⚖", "稳健运营");

    private final String icon;
    private final String label;

    PilotMode(String icon, String label) {
        this.icon = icon;
        this.label = label;
    }

    public String icon() {
        return icon;
    }

    public String label() {
        return label;
    }

    /** @return 未知/空返回 BALANCED（容错） */
    public static PilotMode fromName(String name) {
        if (name == null) {
            return BALANCED;
        }
        for (PilotMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name.trim())) {
                return mode;
            }
        }
        return BALANCED;
    }
}
