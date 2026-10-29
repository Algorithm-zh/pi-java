package com.piagent.application.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 认证上下文测试：默认租户回退、线程传播与恢复。
 */
class AuthContextTest {

    private static final TenantId TENANT = TenantId.parse("01TST000000000000000000009");
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(TENANT,
            new UserId(com.piagent.domain.shared.Ulid.generate(Instant.parse("2026-10-29T00:00:00Z"))),
            "alice", "Alice", UserRole.MEMBER);

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void fallsBackToDefaultTenantWithoutBinding() {
        assertThat(AuthContext.tenantId()).isEqualTo(TenantId.DEFAULT);
        assertThat(AuthContext.principal()).isEmpty();
    }

    @Test
    void bindingPrincipalAlsoBindsTenant() {
        AuthContext.bind(PRINCIPAL);
        assertThat(AuthContext.principal()).contains(PRINCIPAL);
        assertThat(AuthContext.tenantId()).isEqualTo(TENANT);

        AuthContext.clear();
        assertThat(AuthContext.principal()).isEmpty();
        assertThat(AuthContext.tenantId()).isEqualTo(TenantId.DEFAULT);
    }

    @Test
    void bindTenantWithoutPrincipalKeepsSystemContext() {
        AuthContext.bindTenant(TENANT);
        assertThat(AuthContext.principal()).isEmpty();
        assertThat(AuthContext.tenantId()).isEqualTo(TENANT);
    }

    @Test
    void callAsRestoresPreviousContext() {
        AuthContext.bindTenant(TENANT);
        AuthPrincipal other = AuthPrincipal.system(TenantId.DEFAULT);

        String username = AuthContext.callAs(other, () -> AuthContext.requirePrincipal().username());

        assertThat(username).isEqualTo("system");
        assertThat(AuthContext.principal()).isEmpty();
        assertThat(AuthContext.tenantId()).isEqualTo(TENANT);
    }

    @Test
    void wrapPropagatesPrincipalToAnotherThread() throws Exception {
        AuthContext.bind(PRINCIPAL);
        AtomicReference<AuthPrincipal> seen = new AtomicReference<>();
        Thread thread = new Thread(AuthContext.wrap(() -> seen.set(
                AuthContext.requirePrincipal())));

        thread.start();
        thread.join();

        assertThat(seen.get()).isEqualTo(PRINCIPAL);
        assertThat(AuthContext.principal()).contains(PRINCIPAL);
    }

    @Test
    void wrapPropagatesTenantWhenNoPrincipal() throws Exception {
        AuthContext.bindTenant(TENANT);
        AtomicReference<TenantId> seen = new AtomicReference<>();
        Thread thread = new Thread(AuthContext.wrap(() -> seen.set(AuthContext.tenantId())));

        thread.start();
        thread.join();

        assertThat(seen.get()).isEqualTo(TENANT);
    }

    @Test
    void requirePrincipalRejectsAnonymous() {
        try {
            AuthContext.requirePrincipal();
        } catch (DomainException error) {
            assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_REQUIRED);
            return;
        }
        throw new AssertionError("匿名上下文不应返回主体");
    }
}
