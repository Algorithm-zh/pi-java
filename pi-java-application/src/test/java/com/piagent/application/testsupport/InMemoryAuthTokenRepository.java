package com.piagent.application.testsupport;

import com.piagent.domain.identity.model.AuthToken;
import com.piagent.domain.identity.model.AuthTokenId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.port.AuthTokenRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用层测试用的内存令牌仓储。
 */
public final class InMemoryAuthTokenRepository implements AuthTokenRepository {

    private final List<AuthToken> tokens = new ArrayList<>();

    @Override
    public void insert(AuthToken token) {
        tokens.add(token);
    }

    @Override
    public Optional<AuthToken> findByHash(String tokenHash) {
        return tokens.stream().filter(token -> token.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public void touch(AuthTokenId id, Instant usedAt) {
        replace(id, token -> token.touch(usedAt));
    }

    @Override
    public int revoke(AuthTokenId id, Instant now) {
        int before = tokens.size();
        replace(id, token -> token.revoke(now));
        return tokens.size() == before ? 0 : 1;
    }

    @Override
    public int revokeAllForUser(UserId userId, Instant now) {
        int revoked = 0;
        for (int index = 0; index < tokens.size(); index++) {
            AuthToken token = tokens.get(index);
            if (token.userId().equals(userId) && token.revokedAt() == null) {
                tokens.set(index, token.revoke(now));
                revoked++;
            }
        }
        return revoked;
    }

    @Override
    public int deleteExpired(Instant now) {
        int before = tokens.size();
        tokens.removeIf(token -> token.expiresAt().isBefore(now));
        return before - tokens.size();
    }

    private void replace(AuthTokenId id, java.util.function.UnaryOperator<AuthToken> change) {
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).id().equals(id)) {
                tokens.set(index, change.apply(tokens.get(index)));
                return;
            }
        }
    }
}
