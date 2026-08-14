package com.arena.alliance.auth;

/**
 * 请求上下文中的当前登录用户（AuthInterceptor 填充）。
 */
public record CurrentUser(long id, String username, boolean admin) {

    public static final String ATTR = "alliance.currentUser";
}
