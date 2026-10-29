package com.piagent.domain.identity.port;

import com.piagent.domain.identity.model.AuthToken;
import com.piagent.domain.identity.model.AuthTokenId;
import com.piagent.domain.identity.model.UserId;
import java.time.Instant;
import java.util.Optional;

/**
 * 访问令牌仓储。
 */
public interface AuthTokenRepository {

    /**
     * 插入令牌记录。
     *
     * @param token 令牌
     */
    void insert(AuthToken token);

    /**
     * 按哈希查询令牌。
     *
     * @param tokenHash SHA-256 十六进制哈希
     * @return 令牌，不存在时为空
     */
    Optional<AuthToken> findByHash(String tokenHash);

    /**
     * 更新最近使用时间。
     *
     * @param id 令牌标识
     * @param usedAt 使用时间
     */
    void touch(AuthTokenId id, Instant usedAt);

    /**
     * 吊销单个令牌。
     *
     * @param id 令牌标识
     * @param now 吊销时间
     * @return 影响行数
     */
    int revoke(AuthTokenId id, Instant now);

    /**
     * 吊销账号的全部令牌。
     *
     * @param userId 账号
     * @param now 吊销时间
     * @return 影响行数
     */
    int revokeAllForUser(UserId userId, Instant now);

    /**
     * 删除过期令牌记录。
     *
     * @param now 当前时间
     * @return 删除行数
     */
    int deleteExpired(Instant now);
}
