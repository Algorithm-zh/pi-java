package com.piagent.trigger.http.auth;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.service.AuthApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权 REST 控制器。
 *
 * <p>首次初始化与登录成功时同时返回令牌并下发 HttpOnly Cookie；登出吊销令牌并
 * 清除 Cookie。状态接口不需要令牌，供 Web UI 决定显示初始化、登录还是主界面。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService auth;
    private final Duration tokenTtl;
    private final boolean registrationEnabled;

    /**
     * 创建控制器。
     *
     * @param auth 鉴权用例
     * @param tokenTtl 令牌有效期，用于 Cookie 过期时间
     * @param registrationEnabled 是否开放自助注册
     */
    public AuthController(AuthApplicationService auth,
            @Value("${pi.auth.token-ttl:7d}") Duration tokenTtl,
            @Value("${pi.auth.registration-enabled:true}") boolean registrationEnabled) {
        this.auth = auth;
        this.tokenTtl = tokenTtl;
        this.registrationEnabled = registrationEnabled;
    }

    /**
     * 查询初始化与登录状态。
     *
     * <p>该接口不经过鉴权过滤器，需自行校验令牌，否则页面刷新后会误判为未登录。</p>
     *
     * @param request HTTP 请求
     * @return 状态
     */
    @GetMapping("/state")
    public AuthStateResponse state(HttpServletRequest request) {
        if (!auth.initialized()) {
            return new AuthStateResponse(false, false, null, registrationEnabled);
        }
        return auth.authenticate(AuthCookies.token(request))
                .map(principal -> new AuthStateResponse(true, true,
                        AuthUserResponse.from(AuthContext.callAs(principal, auth::me)),
                        registrationEnabled))
                .orElseGet(() -> new AuthStateResponse(true, false, null, registrationEnabled));
    }

    /**
     * 首次初始化管理员并直接登录。
     *
     * @param request 初始化请求
     * @param httpRequest HTTP 请求
     * @param httpResponse HTTP 响应
     * @return 登录会话
     */
    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthSessionResponse setup(@RequestBody SetupRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AuthSessionView session = auth.setup(request.toCommand());
        setCookie(httpResponse, session.token(), httpRequest.isSecure());
        return AuthSessionResponse.from(session);
    }

    /**
     * 登录并签发令牌。
     *
     * @param request 登录请求
     * @param httpRequest HTTP 请求
     * @param httpResponse HTTP 响应
     * @return 登录会话
     */
    @PostMapping("/login")
    public AuthSessionResponse login(@RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AuthSessionView session = auth.login(request.toCommand());
        setCookie(httpResponse, session.token(), httpRequest.isSecure());
        return AuthSessionResponse.from(session);
    }

    /**
     * 自助注册成员账号并直接登录。
     *
     * @param request 注册请求
     * @param httpRequest HTTP 请求
     * @param httpResponse HTTP 响应
     * @return 登录会话
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthSessionResponse register(@RequestBody RegisterRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AuthSessionView session = auth.register(request.toCommand());
        setCookie(httpResponse, session.token(), httpRequest.isSecure());
        return AuthSessionResponse.from(session);
    }

    /**
     * 登出并清除会话 Cookie。
     *
     * @param httpRequest HTTP 请求
     * @param httpResponse HTTP 响应
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        auth.logout(AuthCookies.token(httpRequest));
        httpResponse.addHeader(HttpHeaders.SET_COOKIE,
                AuthCookies.clear(httpRequest.isSecure()).toString());
    }

    /**
     * 查询当前登录账号。
     *
     * @return 账号信息
     */
    @GetMapping("/me")
    public AuthUserResponse me() {
        return AuthUserResponse.from(auth.me());
    }

    private void setCookie(HttpServletResponse response, String token, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                AuthCookies.issue(token, tokenTtl, secure).toString());
    }
}
