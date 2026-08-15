package com.arena.alliance.auth;

import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.common.AllianceException;
import com.arena.alliance.common.ApiResponse;
import com.arena.alliance.config.AllianceProperties;
import com.arena.alliance.rules.RuleService;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserRepository;
import com.arena.alliance.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String STATE_COOKIE = "oauth_state";

    private final AllianceProperties props;
    private final LinuxDoOAuthService oauth;
    private final UserService userService;
    private final UserRepository userRepository;
    private final RuleService ruleService;
    private final SessionService sessionService;
    private final ApiKeyService apiKeyService;

    public AuthController(AllianceProperties props, LinuxDoOAuthService oauth, UserService userService,
                          UserRepository userRepository, RuleService ruleService, SessionService sessionService,
                          ApiKeyService apiKeyService) {
        this.props = props;
        this.oauth = oauth;
        this.userService = userService;
        this.userRepository = userRepository;
        this.ruleService = ruleService;
        this.sessionService = sessionService;
        this.apiKeyService = apiKeyService;
    }

    /** 登录页所需的公开配置 */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allianceName", ruleService.rules().allianceName);
        m.put("linuxdoConfigured", oauth.configured());
        m.put("devLoginEnabled", props.devLoginEnabled());
        m.put("registrationOpen", ruleService.rules().registrationOpen);
        m.put("passwordRegisterEnabled",
                (ruleService.rules().allowPasswordRegister && ruleService.rules().registrationOpen)
                        || userRepository.count() == 0);
        return ApiResponse.ok(m);
    }

    public record PasswordAuthRequest(String username, String password) {
    }

    /** 账号密码注册（首个用户注册不受开关限制并成为管理员） */
    @PostMapping("/register")
    public ApiResponse<Void> register(@RequestBody PasswordAuthRequest body,
                                     HttpServletRequest request, HttpServletResponse response) {
        boolean allowed = ruleService.rules().allowPasswordRegister && ruleService.rules().registrationOpen;
        User user = userService.registerLocal(body.username(), body.password(), allowed);
        issueSession(request, response, user.getId());
        return ApiResponse.ok();
    }

    /** 账号密码登录 */
    @PostMapping("/login")
    public ApiResponse<Void> login(@RequestBody PasswordAuthRequest body,
                                  HttpServletRequest request, HttpServletResponse response) {
        User user = userService.loginLocal(body.username(), body.password());
        issueSession(request, response, user.getId());
        return ApiResponse.ok();
    }

    @GetMapping("/linuxdo/authorize")
    public void authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!oauth.configured()) {
            redirectError(response, "未配置 LinuxDo OAuth，请管理员设置 LINUXDO_CLIENT_ID/SECRET");
            return;
        }
        String state = Long.toHexString(System.nanoTime()) + Long.toHexString(Double.doubleToLongBits(Math.random()));
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, state)
                .httpOnly(true).path("/").maxAge(Duration.ofMinutes(10)).sameSite("Lax")
                .secure(isHttps(request)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.sendRedirect(oauth.buildAuthorizeUrl(redirectUri(request), state));
    }

    @GetMapping("/linuxdo/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String expectedState = cookieValue(request, STATE_COOKIE);
            if (code == null || state == null || expectedState == null || !expectedState.equals(state)) {
                redirectError(response, "OAuth 状态校验失败，请重试");
                return;
            }
            LinuxDoOAuthService.LinuxDoUser ldUser = oauth.exchange(code, redirectUri(request));
            if (!ldUser.active() || ldUser.silenced()) {
                redirectError(response, "LinuxDo 账号状态异常（未激活或被禁言）");
                return;
            }
            if (ldUser.trustLevel() < ruleService.rules().minTrustLevel) {
                redirectError(response, "LinuxDo 信任等级不足（需要 " + ruleService.rules().minTrustLevel
                        + "，当前 " + ldUser.trustLevel() + "）");
                return;
            }
            User user = userService.loginFromLinuxDo(ldUser.id(), ldUser.username(),
                    ldUser.name(), ldUser.trustLevel(), ruleService.rules().registrationOpen);
            issueSession(request, response, user.getId());
            response.sendRedirect("/");
        } catch (AllianceException e) {
            redirectError(response, e.getMessage());
        } catch (Exception e) {
            log.error("LinuxDo 回调处理失败", e);
            redirectError(response, "登录失败，请稍后重试");
        }
    }

    /** 本地开发登录（默认关闭；alliance.dev-login-enabled=true 时可用） */
    @GetMapping("/dev-login")
    public void devLogin(@RequestParam String u,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!props.devLoginEnabled()) {
            throw AllianceException.forbidden("开发登录未启用");
        }
        User user = userService.loginFromLinuxDo("dev:" + u, u, u, 99, true);
        issueSession(request, response, user.getId());
        response.sendRedirect("/");
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        Long userId = sessionService.verify(AuthInterceptor.readSessionCookie(request));
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            m.put("loggedIn", false);
            return ApiResponse.ok(m);
        }
        m.put("loggedIn", true);
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("displayName", user.getDisplayName());
        m.put("role", user.getRole().name());
        m.put("status", user.getStatus().name());
        m.put("warnings", user.getWarnings());
        m.put("hasGameKey", apiKeyService.hasUploadedKey(user.getId()));
        return ApiResponse.ok(m);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(SessionService.COOKIE_NAME, "")
                .httpOnly(true).path("/").maxAge(0).sameSite("Lax").build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ApiResponse.ok();
    }

    private void issueSession(HttpServletRequest request, HttpServletResponse response, long userId) {
        ResponseCookie cookie = ResponseCookie.from(SessionService.COOKIE_NAME, sessionService.issue(userId))
                .httpOnly(true).path("/").maxAge(sessionService.ttl()).sameSite("Lax")
                // HTTPS 下加 Secure：禁止明文回传，抓包拿不到会话
                .secure(isHttps(request)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 兼容反向代理：优先看 X-Forwarded-Proto */
    static boolean isHttps(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-Proto");
        return forwarded != null ? "https".equalsIgnoreCase(forwarded) : request.isSecure();
    }

    private String redirectUri(HttpServletRequest request) {
        String base = props.linuxdo().redirectBase();
        if (base == null || base.isBlank()) {
            String scheme = request.getHeader("X-Forwarded-Proto");
            if (scheme == null) {
                scheme = request.getScheme();
            }
            base = scheme + "://" + request.getHeader("Host");
        }
        return base + "/api/auth/linuxdo/callback";
    }

    private static void redirectError(HttpServletResponse response, String message) throws IOException {
        response.sendRedirect("/login.html?error=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var c : request.getCookies()) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
