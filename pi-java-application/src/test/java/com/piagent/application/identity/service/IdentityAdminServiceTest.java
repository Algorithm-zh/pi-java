package com.piagent.application.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.command.CreateTenantCommand;
import com.piagent.application.identity.command.CreateUserCommand;
import com.piagent.application.identity.command.LoginCommand;
import com.piagent.application.identity.command.SetupCommand;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.readmodel.TenantSummary;
import com.piagent.application.testsupport.InMemoryAuthTokenRepository;
import com.piagent.application.testsupport.InMemoryTenantRepository;
import com.piagent.application.testsupport.InMemoryUserRepository;
import com.piagent.application.testsupport.MutableClock;
import com.piagent.application.testsupport.PrefixPasswordHasher;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SequentialTokenCodec;
import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 租户与账号管理用例测试：管理员权限、租户内隔离与重名冲突。
 */
class IdentityAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T09:00:00Z");
    private static final String PASSWORD = "secret-pass-1";

    private final InMemoryTenantRepository tenants = new InMemoryTenantRepository();
    private final InMemoryUserRepository users = new InMemoryUserRepository(tenants);
    private final InMemoryAuthTokenRepository tokens = new InMemoryAuthTokenRepository();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final MutableClock clock = new MutableClock(NOW);

    private AuthApplicationService auth;
    private IdentityAdminService admin;
    private AppUser alice;

    @BeforeEach
    void setUp() {
        auth = new AuthApplicationService(tenants, users, tokens, new PrefixPasswordHasher(),
                new SequentialTokenCodec(), ids, clock, Duration.ofDays(7), true);
        admin = new IdentityAdminService(tenants, users, tokens, new PrefixPasswordHasher(), ids,
                clock);
        auth.setup(new SetupCommand("Alice", "Alice", PASSWORD));
        alice = users.findByUsername(TenantId.DEFAULT, "alice").orElseThrow();
        bind(alice);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void adminCreatesTenantWithInitialAdminWhoCanLogIn() {
        var created = admin.createTenant(new CreateTenantCommand(
                "team-b", "团队 B", "Bob", "Bob", PASSWORD));

        assertThat(created.slug()).isEqualTo("team-b");
        Tenant tenant = tenants.findBySlug("team-b").orElseThrow();
        assertThat(admin.listTenants()).hasSize(2);

        AuthSessionView session = auth.login(new LoginCommand("team-b", "bob", PASSWORD));
        AuthPrincipal principal = auth.authenticate(session.token()).orElseThrow();
        assertThat(principal.tenantId()).isEqualTo(tenant.id());
        assertThat(principal.role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void memberCannotManageTenantsOrUsers() {
        admin.createUser(new CreateUserCommand("carol", "Carol", PASSWORD, UserRole.MEMBER));
        AppUser carol = users.findByUsername(TenantId.DEFAULT, "carol").orElseThrow();
        bind(carol);

        assertThatThrownBy(admin::listTenants)
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));
        assertThatThrownBy(() -> admin.createTenant(new CreateTenantCommand(
                "team-b", "团队 B", "Bob", "Bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));
        assertThatThrownBy(admin::listUsers)
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));
        assertThatThrownBy(() -> admin.createUser(
                new CreateUserCommand("dave", "Dave", PASSWORD, UserRole.MEMBER)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));
    }

    @Test
    void requiresLogin() {
        AuthContext.clear();
        assertThatThrownBy(admin::listUsers)
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_REQUIRED));
    }

    @Test
    void rejectsDuplicateSlugAndUsername() {
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B", "Bob", "Bob", PASSWORD));

        assertThatThrownBy(() -> admin.createTenant(new CreateTenantCommand(
                "team-b", "另一个团队", "Eve", "Eve", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_ALREADY_EXISTS));

        assertThatThrownBy(() -> admin.createUser(
                new CreateUserCommand("alice", "Alice 2", PASSWORD, UserRole.MEMBER)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.USER_ALREADY_EXISTS));
    }

    @Test
    void listsUsersOnlyForCurrentTenant() {
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B", "Bob", "Bob", PASSWORD));

        assertThat(admin.listUsers())
                .extracting("username")
                .containsExactly("alice");

        AppUser bob = users.findByUsername(tenants.findBySlug("team-b").orElseThrow().id(), "bob")
                .orElseThrow();
        bind(bob);
        assertThat(admin.listUsers())
                .extracting("username")
                .containsExactly("bob");
    }

    @Test
    void creatingUserRejectsShortPasswordAndMissingRole() {
        assertThatThrownBy(() -> admin.createUser(
                new CreateUserCommand("dave", "Dave", "short", UserRole.MEMBER)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> admin.createUser(
                new CreateUserCommand("dave", "Dave", PASSWORD, null)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    private void bind(AppUser user) {
        AuthContext.bind(new AuthPrincipal(user.tenantId(), user.id(), user.username(),
                user.displayName(), user.role()));
    }

    @Test
    void archivesTenantThenRestoresIt() {
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B", "Bob", "Bob", PASSWORD));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();
        AuthSessionView bobSession = auth.login(new LoginCommand("team-b", "bob", PASSWORD));

        TenantSummary archived = admin.archiveTenant(team.id().toString());
        assertThat(archived.deletedAt()).isNotNull();
        assertThat(tenants.findById(team.id()).orElseThrow().isArchived()).isTrue();
        assertThat(admin.listTenants())
                .filteredOn(summary -> summary.slug().equals("team-b"))
                .extracting(TenantSummary::deletedAt)
                .doesNotContainNull();

        assertThat(auth.authenticate(bobSession.token())).isEmpty();
        assertThatThrownBy(() -> auth.login(new LoginCommand("team-b", "bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS));

        TenantSummary restored = admin.restoreTenant(team.id().toString());
        assertThat(restored.deletedAt()).isNull();
        assertThat(auth.login(new LoginCommand("team-b", "bob", PASSWORD)).token()).isNotBlank();
    }

    @Test
    void cannotArchiveDefaultOrCurrentSpace() {
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B", "Bob", "Bob", PASSWORD));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();

        assertThatThrownBy(() -> admin.archiveTenant(TenantId.DEFAULT.toString()))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_PROTECTED));

        bind(users.findByUsername(team.id(), "bob").orElseThrow());
        assertThatThrownBy(() -> admin.archiveTenant(team.id().toString()))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_PROTECTED));
    }

    @Test
    void archiveRequiresAdminAndKnownTenant() {
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B", "Bob", "Bob", PASSWORD));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();
        admin.createUser(new CreateUserCommand("carol", "Carol", PASSWORD, UserRole.MEMBER));
        bind(users.findByUsername(TenantId.DEFAULT, "carol").orElseThrow());

        assertThatThrownBy(() -> admin.archiveTenant(team.id().toString()))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));

        bind(alice);
        assertThatThrownBy(() -> admin.archiveTenant("01TST9999999999999999999999"))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_NOT_FOUND));
    }
}
