package com.arena.alliance.incident;

/**
 * 事件类型（前端按类型着色/图标）。
 */
public enum IncidentType {
    /** 内部攻击已被覆盖拦截 */
    INTERNAL_ATTACK_BLOCKED,
    /** 检测到内部攻击（拦截功能关闭，仅记录） */
    INTERNAL_ATTACK_DETECTED,
    /** 网页手操（MANUAL）内部攻击，无法拦截 */
    INTERNAL_ATTACK_MANUAL,
    /** 本 Tick 覆盖次数达到上限 */
    OVERRIDE_LIMIT,
    /** 内部攻击造成伤亡 */
    INTERNAL_CASUALTY,
    /** 警告 */
    WARNING,
    /** 踢出联盟 */
    KICK,
    /** 恢复成员资格 */
    RESTORE,
    /** 外部玩家攻击成员 */
    EXTERNAL_ATTACK,
    /** apikey 失效 */
    KEY_INVALID,
    /** apikey 与现有游戏账号重复 */
    KEY_DUPLICATE,
    /** 开启托管 */
    HOSTING_STARTED,
    /** 停止托管 */
    HOSTING_STOPPED,
    /** 检测到成员自己的 agent 在运行，托管冲突暂停 */
    HOSTING_PAUSED_CONFLICT,
    /** 托管恢复 */
    HOSTING_RESUMED,
    /** 一般信息 */
    INFO
}
