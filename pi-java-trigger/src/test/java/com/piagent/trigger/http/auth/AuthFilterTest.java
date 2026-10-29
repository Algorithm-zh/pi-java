package com.piagent.trigger.http.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.service.AuthApplicationService;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.shared.Ulid;
import com.piagent.trigger.http.ApiExceptionHandler;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权过滤器测试：公开路径、Bearer 与 Cookie 提取、主体绑定与 401 响应。
 */
class AuthFilterTest {

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(TenantId.DEFAULT,
            new UserId(Ulid.generate(Instant.parse("2026-10-29T00:00:00Z"))),
            "alice", "Alice", UserRole.MEMBER);

    private AuthApplicationService auth;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        auth = mock(AuthApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new AuthFilter(auth, new ObjectMapper()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void rejectsMissingToken() throws Exception {
        when(auth.authenticate(isNull())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownToken() throws Exception {
        when(auth.authenticate("bad")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsBearerTokenAndBindsPrincipal() throws Exception {
        when(auth.authenticate("good")).thenReturn(Optional.of(PRINCIPAL));

        String body = mvc.perform(get("/api/v1/probe")
                        .header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("alice@" + TenantId.DEFAULT);
        assertThat(AuthContext.principal()).isEmpty();
    }

    @Test
    void acceptsSessionCookie() throws Exception {
        when(auth.authenticate("good")).thenReturn(Optional.of(PRINCIPAL));

        mvc.perform(get("/api/v1/probe").cookie(new Cookie(AuthCookies.NAME, "good")))
                .andExpect(status().isOk());
    }

    @Test
    void bearerHeaderTakesPrecedenceOverCookie() throws Exception {
        when(auth.authenticate("bad")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/probe")
                        .header("Authorization", "Bearer bad")
                        .cookie(new Cookie(AuthCookies.NAME, "good")))
                .andExpect(status().isUnauthorized());

        verify(auth).authenticate("bad");
        verify(auth, never()).authenticate("good");
    }

    @Test
    void skipsPublicAndNonApiPaths() throws Exception {
        mvc.perform(get("/api/v1/auth/state")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/register")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());

        verify(auth, never()).authenticate(any());
    }

    /**
     * 返回当前主体，用于验证过滤器绑定结果。
     */
    @RestController
    static class ProbeController {

        /**
         * 受保护路径。
         *
         * @return 用户名与租户
         */
        @GetMapping("/api/v1/probe")
        String probe() {
            AuthPrincipal principal = AuthContext.requirePrincipal();
            return principal.username() + "@" + principal.tenantId();
        }

        /**
         * 公开路径样例。
         *
         * @return 固定文本
         */
        @GetMapping("/api/v1/auth/state")
        String state() {
            return "public";
        }

        /**
         * 自助注册也是公开路径。
         *
         * @return 固定文本
         */
        @PostMapping("/api/v1/auth/register")
        String register() {
            return "public";
        }

        /**
         * 非 API 路径样例。
         *
         * @return 固定文本
         */
        @GetMapping("/v3/api-docs")
        String docs() {
            return "docs";
        }
    }
}
