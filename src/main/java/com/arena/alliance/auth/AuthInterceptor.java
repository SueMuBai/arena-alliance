package com.arena.alliance.auth;

import com.arena.alliance.user.User;
import com.arena.alliance.user.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * /api/** 登录校验：
 * - /api/auth/** 放行（其内部自行处理可选会话）
 * - /api/admin/** 需要管理员
 * - 被踢成员禁止访问地图数据
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final SessionService sessionService;
    private final UserRepository userRepository;

    public AuthInterceptor(SessionService sessionService, UserRepository userRepository) {
        this.sessionService = sessionService;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/") || path.startsWith("/api/alliance/")) {
            // /api/alliance/** 供成员 agent 用接入令牌访问，控制器内自行校验
            return true;
        }
        Long userId = sessionService.verify(readSessionCookie(request));
        if (userId == null) {
            return reject(response, 401, "未登录");
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return reject(response, 401, "用户不存在");
        }
        if (user.getStatus() == User.Status.BANNED) {
            return reject(response, 403, "账号已被封禁");
        }
        if (path.startsWith("/api/admin/") && !user.isAdmin()) {
            return reject(response, 403, "需要管理员权限");
        }
        if (user.getStatus() == User.Status.KICKED && path.startsWith("/api/map/")) {
            return reject(response, 403, "你已被移出联盟，无法查看联盟地图");
        }
        request.setAttribute(CurrentUser.ATTR, new CurrentUser(user.getId(), user.getUsername(), user.isAdmin()));
        return true;
    }

    static String readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (SessionService.COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(
                ("{\"success\":false,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
