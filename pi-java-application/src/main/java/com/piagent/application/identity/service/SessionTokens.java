package com.piagent.application.identity.service;

import com.piagent.application.identity.port.TokenCodec;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.readmodel.AuthUserView;
import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.AuthToken;
import com.piagent.domain.identity.model.AuthTokenId;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.port.AuthTokenRepository;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 登录令牌的签发、解析与吊销。
 *
 * <p>明文只出现在响应里，仓储只保存哈希；解析时顺带节流更新最近使用时间。
 * 所有调用都发生在调用方的事务内。</p>
 */
class SessionTokens {

    private static final int MAX_TOKEN_LENGTH = 512;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final System.Logger LOG =
            System.getLogger(SessionTokens.class.getName());

    private final AuthTokenRepository tokens;
    private final TokenCodec tokenCodec;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final Duration ttl;

    SessionTokens(AuthTokenRepository tokens, TokenCodec tokenCodec, IdGenerator idGenerator,
            Clock clock, Duration ttl) {
        this.tokens = tokens;
        this.tokenCodec = tokenCodec;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * 签发令牌并组装登录会话。
     *
     * @param tenant 当前空间
     * @param user 登录账号
     * @param role 账号在该空间的角色
     * @return 新登录会话
     */
    AuthSessionView issue(Tenant tenant, AppUser user, UserRole role) {
        String raw = tokenCodec.newToken();
        Instant now = clock.instant();
        AuthToken token = AuthToken.issue(AuthTokenId.newId(idGenerator), tenant.id(), user.id(),
                tokenCodec.hash(raw), now, now.plus(ttl));
        tokens.insert(token);
        return new AuthSessionView(raw, AuthUserView.of(user, tenant, role));
    }

    /**
     * 解析仍可用的令牌；格式无效、过期或已吊销时为空。
     *
     * @param raw 令牌明文
     * @return 令牌
     */
    Optional<AuthToken> resolve(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tokens.findByHash(tokenCodec.hash(raw.strip()))
                .filter(token -> token.isUsableAt(now))
                .map(token -> touch(token, now));
    }

    /**
     * 吊销令牌；格式无效或不存在时静默返回。
     *
     * @param raw 令牌明文
     */
    void revoke(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_TOKEN_LENGTH) {
            return;
        }
        tokens.findByHash(tokenCodec.hash(raw.strip()))
                .ifPresent(token -> tokens.revoke(token.id(), clock.instant()));
    }

    private AuthToken touch(AuthToken token, Instant now) {
        if (token.lastUsedAt() != null && now.isBefore(token.lastUsedAt().plus(TOUCH_INTERVAL))) {
            return token;
        }
        try {
            tokens.touch(token.id(), now);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "更新令牌使用时间失败: " + token.id(), error);
        }
        return token;
    }
}
