package com.piagent.app.support;

import com.piagent.application.identity.command.LoginCommand;
import com.piagent.application.identity.command.SetupCommand;
import com.piagent.application.identity.service.AuthApplicationService;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.auth.AuthCookies;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 集成测试鉴权装配。
 *
 * <p>M19 之后所有 {@code /api/**} 请求都要携带令牌，因此需要一个真实登录会话。
 * 首次使用时以固定账号初始化，之后复用已有账号登录，会话 Cookie 通过
 * {@link MockMvcBuilderCustomizer} 注入到每个请求；测试仍走真实的鉴权过滤器。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthenticatedMockMvcConfig {

    /** 集成测试使用的管理员登录名，审批审计断言可引用。 */
    public static final String ADMIN_USERNAME = "it-admin";

    private static final String PASSWORD = "it-password-123";

    /**
     * 登录会话辅助器。
     *
     * @param auth 鉴权用例
     * @return 辅助器
     */
    @Bean
    AuthSession authSession(AuthApplicationService auth) {
        return new AuthSession(auth);
    }

    /**
     * 为每个 MockMvc 请求注入登录 Cookie。
     *
     * @param session 登录会话
     * @return 定制器
     */
    @Bean
    MockMvcBuilderCustomizer sessionCookie(AuthSession session) {
        return builder -> builder.defaultRequest(
                MockMvcRequestBuilders.get("/").cookie(session.cookie()));
    }

    /**
     * 惰性登录并缓存 Cookie。
     */
    public static final class AuthSession {

        private final AuthApplicationService auth;
        private Cookie cookie;

        /**
         * 创建辅助器。
         *
         * @param auth 鉴权用例
         */
        public AuthSession(AuthApplicationService auth) {
            this.auth = auth;
        }

        /**
         * 获取登录 Cookie。
         *
         * @return 会话 Cookie
         */
        public synchronized Cookie cookie() {
            if (cookie == null) {
                cookie = new Cookie(AuthCookies.NAME, token());
            }
            return cookie;
        }

        private String token() {
            try {
                return auth.setup(new SetupCommand(ADMIN_USERNAME, "集成测试管理员", PASSWORD)).token();
            } catch (DomainException error) {
                if (error.code() != DomainErrorCode.AUTH_ALREADY_INITIALIZED) {
                    throw error;
                }
            }
            return auth.login(new LoginCommand(Tenant.DEFAULT_SLUG, ADMIN_USERNAME, PASSWORD)).token();
        }
    }
}
