package com.arena.alliance.rules;

/**
 * 联盟规则（管理员后台可改）。字段即规则项，JSON 序列化直接入库。
 */
public class RuleSettings {

    /** 联盟名称（地图页展示） */
    public String allianceName = "Arena 联盟";

    /** 是否启用内部攻击实时拦截（用攻击者自己的 key 覆盖其攻击指令） */
    public boolean interventionEnabled = true;

    /** 每个成员每 Tick 最多覆盖次数（服务器限制同槽位每 Tick 64 次提交，留余量防打满） */
    public int maxOverridesPerTick = 8;

    /** 内部攻击致对方任一单位死亡 → 踢出 */
    public boolean kickOnUnitDeath = true;

    /** 内部攻击致对方 Core 掉 HP → 踢出 */
    public boolean kickOnCoreDamage = true;

    /** 内部攻击致对方 Core 被摧毁 → 踢出 */
    public boolean kickOnCoreDestroyed = true;

    /** 内部攻击仅打掉护盾/单位掉血未死 → 记一次警告 */
    public boolean warnOnShieldDamage = true;

    /** 警告累计 N 次自动踢出 */
    public int kickAfterWarnings = 3;

    /** 外部玩家攻击成员时在地图与事件流告警 */
    public boolean externalAlert = true;

    /** LinuxDo 登录最低信任等级 */
    public int minTrustLevel = 0;

    /** 是否开放新成员注册（关闭后仅已有成员可登录） */
    public boolean registrationOpen = true;

    /** 允许账号密码注册（关闭时仅 LinuxDo 登录；用户数为 0 时首个注册不受限） */
    public boolean allowPasswordRegister = false;
}
