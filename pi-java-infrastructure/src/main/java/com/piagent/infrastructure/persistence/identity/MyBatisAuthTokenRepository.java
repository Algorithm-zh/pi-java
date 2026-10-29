package com.piagent.infrastructure.persistence.identity;

import com.piagent.domain.identity.model.AuthToken;
import com.piagent.domain.identity.model.AuthTokenId;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.port.AuthTokenRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的访问令牌仓储。
 */
@Repository
public class MyBatisAuthTokenRepository implements AuthTokenRepository {

    private final AuthTokenMapper mapper;
    private final AuthTokenPoConverter converter = new AuthTokenPoConverter();

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     */
    public MyBatisAuthTokenRepository(AuthTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(AuthToken token) {
        mapper.insert(converter.toPo(token));
    }

    @Override
    public Optional<AuthToken> findByHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findByHash(tokenHash)).map(converter::toDomain);
    }

    @Override
    public void touch(AuthTokenId id, Instant usedAt) {
        mapper.touch(id.toString(), usedAt);
    }

    @Override
    public int revoke(AuthTokenId id, Instant now) {
        return mapper.revoke(id.toString(), now);
    }

    @Override
    public int revokeAllForUser(UserId userId, Instant now) {
        return mapper.revokeAllForUser(userId.toString(), now);
    }

    @Override
    public int revokeAllForTenant(TenantId tenantId, Instant now) {
        return mapper.revokeAllForTenant(tenantId.toString(), now);
    }

    @Override
    public int deleteExpired(Instant now) {
        return mapper.deleteExpired(now);
    }
}
