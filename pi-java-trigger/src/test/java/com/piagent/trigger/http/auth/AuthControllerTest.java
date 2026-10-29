package com.piagent.trigger.http.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.readmodel.AuthUserView;
import com.piagent.application.identity.service.AuthApplicationService;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 鉴权控制器测试：状态判定、初始化/登录下发 Cookie、登出清理与错误映射。
 */
class AuthControllerTest {

    private static final AuthUserView USER = new AuthUserView("01TST000000000000000000011",
            "alice", "Alice", "ADMIN", TenantId.DEFAULT.toString(), "default", "默认空间");
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(TenantId.DEFAULT,
            new UserId(Ulid.generate(Instant.parse("2026-10-29T00:00:00Z"))),
            "alice", "Alice", UserRole.ADMIN);

    private AuthApplicationService auth;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        auth = mock(AuthApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, Duration.ofDays(7)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void stateReportsInitializationAndLogin() throws Exception {
        when(auth.initialized()).thenReturn(false);
        mvc.perform(get("/api/v1/auth/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(false))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist());

        when(auth.initialized()).thenReturn(true);
        mvc.perform(get("/api/v1/auth/state"))
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.authenticated").value(false));

        when(auth.authenticate("tok-1")).thenReturn(Optional.of(PRINCIPAL));
        when(auth.me()).thenReturn(USER);
        mvc.perform(get("/api/v1/auth/state")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME, "tok-1")))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.username").value("alice"));
    }

    @Test
    void setupReturnsTokenAndSetsHttpOnlyCookie() throws Exception {
        when(auth.setup(any())).thenReturn(new AuthSessionView("tok-1", USER));

        mvc.perform(post("/api/v1/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","displayName":"Alice",
                                 "password":"secret-pass-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok-1"))
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(cookie().value(AuthCookies.NAME, "tok-1"))
                .andExpect(cookie().httpOnly(AuthCookies.NAME, true))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("SameSite=Strict")));
    }

    @Test
    void loginMapsBadCredentialsTo401() throws Exception {
        when(auth.login(any())).thenThrow(new DomainException(
                DomainErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码不正确"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantSlug\":\"default\",\"username\":\"alice\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void logoutRevokesTokenAndClearsCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME, "tok-1")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.NAME, 0));

        verify(auth).logout("tok-1");
    }

    @Test
    void meRequiresAuthenticatedPrincipal() throws Exception {
        doThrow(new DomainException(DomainErrorCode.AUTH_REQUIRED, "请先登录")).when(auth).me();
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        AuthContext.bind(PRINCIPAL);
        doReturn(USER).when(auth).me();
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantSlug").value("default"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void malformedBodyMapsTo400() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
