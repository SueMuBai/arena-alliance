package com.arena.alliance.game.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * received 消息载荷：服务器当前为某来源槽位存储的作战计划。
 * plan 保留原始 JSON，便于"外科手术式"改写后原样回传。
 */
public record PlanReceipt(long tick, String source, String receivedAt, ObjectNode plan) {

    public boolean isAgent() {
        return "AGENT".equals(source);
    }

    public boolean isManual() {
        return "MANUAL".equals(source);
    }
}
