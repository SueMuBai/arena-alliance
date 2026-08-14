package com.arena.alliance.auth;

import com.arena.alliance.apikey.ApiKeyService;
import com.arena.alliance.user.User;
import com.arena.alliance.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {

    private SessionService sessions;
    private ApiKeyService apiKeys;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        UserRepository users = mock(UserRepository.class);
        apiKeys = mock(ApiKeyService.class);
        interceptor = new AuthInterceptor(sessions, users, apiKeys);

        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getUsername()).thenReturn("member");
        when(user.getStatus()).thenReturn(User.Status.ACTIVE);
        when(user.isAdmin()).thenReturn(false);
        when(sessions.verify("session-token")).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    @Test
    void mapApiRejectsUserWithoutUploadedKey() throws Exception {
        MockHttpServletRequest request = request("/api/map/snapshot");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("请先上传一个游戏 Key"));
    }

    @Test
    void mapPageRedirectsUserWithoutUploadedKey() throws Exception {
        MockHttpServletRequest request = request("/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals("/keys.html?required=map", response.getRedirectedUrl());

        MockHttpServletResponse indexResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("/index.html"), indexResponse, new Object()));
        assertEquals("/keys.html?required=map", indexResponse.getRedirectedUrl());
    }

    @Test
    void mapAccessIsAllowedAfterUploadingKey() throws Exception {
        when(apiKeys.hasUploadedKey(7L)).thenReturn(true);
        MockHttpServletRequest request = request("/api/map/snapshot");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertInstanceOf(CurrentUser.class, request.getAttribute(CurrentUser.ATTR));
    }

    @Test
    void publicRulesRemainAvailableWithoutUploadedKey() throws Exception {
        MockHttpServletRequest request = request("/api/map/rules");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setCookies(new Cookie(SessionService.COOKIE_NAME, "session-token"));
        return request;
    }
}
