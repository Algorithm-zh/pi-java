package com.piagent.trigger.http.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.service.AuthApplicationService;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.trigger.http.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API 鉴权过滤器。
 *
 * <p>除首次初始化、登录、登出与状态查询外，所有 {@code /api/**} 请求都必须携带
 * 有效令牌。校验通过后在当前请求线程绑定主体，仓储据此追加租户条件，请求结束时
 * 清理线程上下文。静态资源与 OpenAPI 文档不在过滤范围内。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/state",
            "/api/v1/auth/setup",
            "/api/v1/auth/login",
            "/api/v1/auth/logout");

    private final AuthApplicationService auth;
    private final ObjectMapper objectMapper;

    /**
     * 创建过滤器。
     *
     * @param auth 鉴权用例
     * @param objectMapper JSON 序列化
     */
    public AuthFilter(AuthApplicationService auth, ObjectMapper objectMapper) {
        this.auth = auth;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(API_PREFIX) || PUBLIC_PATHS.contains(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Optional<AuthPrincipal> principal = auth.authenticate(AuthCookies.token(request));
        if (principal.isEmpty()) {
            unauthorized(response);
            return;
        }
        AuthContext.bind(principal.get());
        try {
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
                DomainErrorCode.AUTH_REQUIRED.name(), "请先登录"));
    }
}
