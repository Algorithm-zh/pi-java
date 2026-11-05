package com.piagent.application.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.command.CreateTenantCommand;
import com.piagent.application.identity.command.CreateUserCommand;
import com.piagent.application.identity.command.InviteMemberCommand;
import com.piagent.application.identity.command.LoginCommand;
import com.piagent.application.identity.command.SetupCommand;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.readmodel.TenantSummary;
import com.piagent.application.testsupport.InMemoryAuthTokenRepository;
import com.piagent.application.testsupport.InMemoryMembershipRepository;
import com.piagent.application.testsupport.InMemoryTenantRepository;
import com.piagent.application.testsupport.InMemoryUserRepository;
import com.piagent.application.testsupport.MutableClock;
import com.piagent.application.testsupport.PrefixPasswordHasher;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SequentialTokenCodec;
import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.SpaceMember;
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
 * 空间与成员管理用例测试：创建团队空间、邀请成员、角色权限与空间保护。
 */
class IdentityAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T09:00:00Z");
    private static final String PASSWORD = "secret-pass-1";

    private final InMemoryTenantRepository tenants = new InMemoryTenantRepository();
    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final InMemoryMembershipRepository memberships = new InMemoryMembershipRepository();
    private final InMemoryAuthTokenRepository tokens = new InMemoryAuthTokenRepository();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final MutableClock clock = new MutableClock(NOW);

    private AuthApplicationService auth;
    private IdentityAdminService admin;
    private AppUser alice;

    @BeforeEach
    void setUp() {
        auth = new AuthApplicationService(tenants, users, memberships, tokens,
                new PrefixPasswordHasher(), new SequentialTokenCodec(), ids, clock,
                Duration.ofDays(7), true);
        admin = new IdentityAdminService(tenants, users, memberships, tokens,
                new PrefixPasswordHasher(), ids, clock);
        auth.setup(new SetupCommand("Alice", "Alice", PASSWORD));
        alice = users.findByUsername("alice").get(0);
        bind(alice, TenantId.DEFAULT, UserRole.ADMIN);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void anyUserCreatesTeamSpaceAndBecomesItsAdmin() {
        TenantSummary created = admin.createTenant(new CreateTenantCommand("team-b", "团队 B"));

        assertThat(created.slug()).isEqualTo("team-b");
        assertThat(created.kind()).isEqualTo("TEAM");
        assertThat(created.role()).isEqualTo("ADMIN");
        Tenant team = tenants.findBySlug("team-b").orElseThrow();
        assertThat(memberships.find(team.id(), alice.id())).get()
                .extracting(SpaceMember::role).isEqualTo(UserRole.ADMIN);
        assertThat(admin.listTenants()).extracting(TenantSummary::slug)
                .containsExactly("default", "u-alice-" + tail(alice), "team-b");

        AuthSessionView session = auth.login(new LoginCommand("team-b", "alice", PASSWORD));
        assertThat(session.user().role()).isEqualTo("ADMIN");
    }

    @Test
    void plainMemberCanCreateTeamSpaceButCannotManageDefaultSpace() {
        admin.createUser(new CreateUserCommand("carol", "Carol", PASSWORD, UserRole.MEMBER));
        AppUser carol = users.findByUsername("carol").get(0);
        bind(carol, TenantId.DEFAULT, UserRole.MEMBER);

        assertThatThrownBy(admin::listUsers)
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));

        TenantSummary created = admin.createTenant(new CreateTenantCommand("team-c", "团队 C"));
        assertThat(created.role()).isEqualTo("ADMIN");
        assertThat(admin.listTenants()).hasSize(3);
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
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B"));

        assertThatThrownBy(() -> admin.createTenant(new CreateTenantCommand("team-b", "另一个团队")))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_ALREADY_EXISTS));

        assertThatThrownBy(() -> admin.createUser(
                new CreateUserCommand("alice", "Alice 2", PASSWORD, UserRole.MEMBER)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.USER_ALREADY_EXISTS));
    }

    @Test
    void createdAccountJoinsCurrentSpaceAndGetsPersonalSpace() {
        admin.createUser(new CreateUserCommand("carol", "Carol", PASSWORD, UserRole.MEMBER));
        AppUser carol = users.findByUsername("carol").get(0);

        assertThat(memberships.find(TenantId.DEFAULT, carol.id())).get()
                .extracting(SpaceMember::role).isEqualTo(UserRole.MEMBER);
        assertThat(memberships.findByUser(carol.id())).hasSize(2);
        assertThat(admin.listTenants()).extracting(TenantSummary::slug)
                .contains("default", "u-alice-" + tail(alice));
    }

    @Test
    void listsUsersOnlyForCurrentSpace() {
        admin.createUser(new CreateUserCommand("bob", "Bob", PASSWORD, UserRole.MEMBER));

        assertThat(admin.listUsers()).extracting("username")
                .containsExactly("alice", "bob");

        admin.createTenant(new CreateTenantCommand("team-b", "团队 B"));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();
        bind(alice, team.id(), UserRole.ADMIN);
        assertThat(admin.listUsers()).extracting("username").containsExactly("alice");
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

    @Test
    void invitesExistingAccountByLookup() {
        admin.createUser(new CreateUserCommand("bob", "Bob", PASSWORD, UserRole.MEMBER));
        AppUser bob = users.findByUsername("bob").get(0);
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B"));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();
        bind(alice, team.id(), UserRole.ADMIN);

        assertThat(admin.findInvitable("bob")).extracting("username").containsExactly("bob");
        assertThat(admin.findInvitable("alice")).isEmpty();
        assertThat(admin.findInvitable("nobody")).isEmpty();

        var invited = admin.inviteMember(team.id().toString(),
                new InviteMemberCommand(bob.id().toString(), UserRole.MEMBER));
        assertThat(invited.username()).isEqualTo("bob");
        assertThat(invited.role()).isEqualTo("MEMBER");
        assertThat(admin.findInvitable("bob")).isEmpty();

        AuthSessionView session = auth.login(new LoginCommand("team-b", "bob", PASSWORD));
        assertThat(session.user().tenantSlug()).isEqualTo("team-b");

        assertThatThrownBy(() -> admin.inviteMember(team.id().toString(),
                new InviteMemberCommand(bob.id().toString(), UserRole.MEMBER)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.USER_ALREADY_EXISTS));
    }

    @Test
    void inviteRejectsUnknownAccountAndNonAdmin() {
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B"));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();

        assertThatThrownBy(() -> admin.inviteMember(team.id().toString(),
                new InviteMemberCommand("not-a-ulid", UserRole.MEMBER)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.USER_NOT_FOUND));

        admin.createUser(new CreateUserCommand("carol", "Carol", PASSWORD, UserRole.MEMBER));
        AppUser carol = users.findByUsername("carol").get(0);
        bind(carol, TenantId.DEFAULT, UserRole.MEMBER);
        assertThatThrownBy(() -> admin.inviteMember(team.id().toString(),
                new InviteMemberCommand(alice.id().toString(), UserRole.MEMBER)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));
    }

    @Test
    void archivesTenantThenRestoresIt() {
        admin.createUser(new CreateUserCommand("bob", "Bob", PASSWORD, UserRole.MEMBER));
        AppUser bob = users.findByUsername("bob").get(0);
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B"));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();
        bind(alice, team.id(), UserRole.ADMIN);
        admin.inviteMember(team.id().toString(),
                new InviteMemberCommand(bob.id().toString(), UserRole.MEMBER));
        bind(alice, TenantId.DEFAULT, UserRole.ADMIN);
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
        assertThatThrownBy(() -> admin.inviteMember(team.id().toString(),
                new InviteMemberCommand(bob.id().toString(), UserRole.MEMBER)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_PROTECTED));

        TenantSummary restored = admin.restoreTenant(team.id().toString());
        assertThat(restored.deletedAt()).isNull();
        assertThat(auth.login(new LoginCommand("team-b", "bob", PASSWORD)).token()).isNotBlank();
    }

    @Test
    void cannotArchiveDefaultPersonalOrCurrentSpace() {
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B"));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();

        assertThatThrownBy(() -> admin.archiveTenant(TenantId.DEFAULT.toString()))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_PROTECTED));

        String personalId = admin.listTenants().stream()
                .filter(summary -> summary.kind().equals("PERSONAL"))
                .findFirst().orElseThrow().id();
        assertThatThrownBy(() -> admin.archiveTenant(personalId))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_PROTECTED));

        bind(alice, team.id(), UserRole.ADMIN);
        assertThatThrownBy(() -> admin.archiveTenant(team.id().toString()))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_PROTECTED));
    }

    @Test
    void archiveRequiresAdminAndKnownTenant() {
        admin.createTenant(new CreateTenantCommand("team-b", "团队 B"));
        Tenant team = tenants.findBySlug("team-b").orElseThrow();
        admin.createUser(new CreateUserCommand("carol", "Carol", PASSWORD, UserRole.MEMBER));
        AppUser carol = users.findByUsername("carol").get(0);
        bind(carol, TenantId.DEFAULT, UserRole.MEMBER);

        assertThatThrownBy(() -> admin.archiveTenant(team.id().toString()))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));

        bind(alice, TenantId.DEFAULT, UserRole.ADMIN);
        assertThatThrownBy(() -> admin.archiveTenant("01TST9999999999999999999999"))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.TENANT_NOT_FOUND));
    }

    private void bind(AppUser user, TenantId tenantId, UserRole role) {
        AuthContext.bind(new AuthPrincipal(tenantId, user.id(), user.username(),
                user.displayName(), role));
    }

    private String tail(AppUser user) {
        String id = user.id().toString();
        return id.substring(id.length() - 4);
    }
}
