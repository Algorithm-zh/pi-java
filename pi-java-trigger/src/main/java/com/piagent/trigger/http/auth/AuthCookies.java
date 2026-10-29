package com.piagent.trigger.http.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * 会话 Cookie 与令牌提取。
 *
 * <p>浏览器使用 HttpOnly + SameSite=Strict 的 Cookie，脚本无法读取；命令行与
 * 第三方客户端使用 {@code Authorization: Bearer} 头。两种方式承载同一个令牌。</p>
 */
public final class AuthCookies {

    /** 会话 Cookie 名称。 */
    public static final String NAME = "pi_session";

    private static final String BEARER_PREFIX = "Bearer ";

    private AuthCookies() {
    }

    /**
     * 从请求中提取令牌：优先 Bearer 头，其次会话 Cookie。
     *
     * @param request HTTP 请求
     * @return 令牌明文；不存在时返回 null
     */
    public static String token(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0,
                BEARER_PREFIX.length())) {
            String raw = header.substring(BEARER_PREFIX.length()).strip();
            return raw.isEmpty() ? null : raw;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 构造会话 Cookie。
     *
     * @param token 令牌明文
     * @param ttl 有效期
     * @param secure 是否仅在 HTTPS 下发送
     * @return 响应 Cookie
     */
    public static ResponseCookie issue(String token, Duration ttl, boolean secure) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(ttl)
                .build();
    }

    /**
     * 构造清除会话 Cookie 的指令。
     *
     * @param secure 是否仅在 HTTPS 下发送
     * @return 响应 Cookie
     */
    public static ResponseCookie clear(boolean secure) {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
