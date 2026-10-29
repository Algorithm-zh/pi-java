package com.piagent.infrastructure.persistence.identity;

import com.piagent.domain.identity.model.AuthToken;
import com.piagent.domain.identity.model.AuthTokenId;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;

/**
 * `pi_auth_token` 行与令牌记录之间的转换。
 */
public final class AuthTokenPoConverter {

    /**
     * 记录转持久化对象。
     *
     * @param token 令牌记录
     * @return 持久化对象
     */
    public AuthTokenPO toPo(AuthToken token) {
        AuthTokenPO po = new AuthTokenPO();
        po.setId(token.id().toString());
        po.setTenantId(token.tenantId().toString());
        po.setUserId(token.userId().toString());
        po.setTokenHash(token.tokenHash());
        po.setIssuedAt(token.issuedAt());
        po.setExpiresAt(token.expiresAt());
        po.setLastUsedAt(token.lastUsedAt());
        po.setRevokedAt(token.revokedAt());
        return po;
    }

    /**
     * 持久化对象转记录。
     *
     * @param po 持久化对象
     * @return 令牌记录
     */
    public AuthToken toDomain(AuthTokenPO po) {
        return new AuthToken(AuthTokenId.parse(po.getId()), TenantId.parse(po.getTenantId()),
                UserId.parse(po.getUserId()), po.getTokenHash(), po.getIssuedAt(),
                po.getExpiresAt(), po.getLastUsedAt(), po.getRevokedAt());
    }
}
