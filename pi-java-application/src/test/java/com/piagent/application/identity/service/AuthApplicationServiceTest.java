package com.piagent.application.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.command.LoginCommand;
import com.piagent.application.identity.command.RegisterCommand;
import com.piagent.application.identity.command.SetupCommand;
import com.piagent.application.identity.command.SwitchSpaceCommand;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.readmodel.TenantChoice;
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
import com.piagent.domain.identity.model.TenantKind;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.model.UserStatus;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 本地鉴权用例测试：初始化、多空间登录、切换空间、锁定、令牌过期与登出。
 *
 * <p>使用内存仓储与可推进时钟，不启动 Spring；密码哈希与令牌编码用应用层
 * 测试替身，PBKDF2 与 SHA-256 适配器另有单元测试。</p>
 */
class AuthApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String PASSWORD = "secret-pass-1";

    private final InMemoryTenantRepository tenants = new InMemoryTenantRepository();
    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final InMemoryMembershipRepository memberships = new InMemoryMembershipRepository();
    private final InMemoryAuthTokenRepository tokens = new InMemoryAuthTokenRepository();
    private final MutableClock clock = new MutableClock(NOW);
    private final SequentialTokenCodec tokenCodec = new SequentialTokenCodec();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();

    private AuthApplicationService service;
    private AppUser alice;

    @BeforeEach
    void setUp() {
        service = newService(true);
        AuthSessionView session = service.setup(new SetupCommand("Alice", "Alice", PASSWORD));
        alice = users.findByUsername("alice").get(0);
        assertThat(session.user().username()).isEqualTo("alice");
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void setupCreatesDefaultTenantPersonalSpaceAndIssuesUsableToken() {
        assertThat(service.initialized()).isTrue();
        Tenant tenant = tenants.findById(TenantId.DEFAULT).orElseThrow();
        assertThat(tenant.slug()).isEqualTo(Tenant.DEFAULT_SLUG);
        assertThat(tenant.kind()).isEqualTo(TenantKind.DEFAULT);
        assertThat(personalSpaceOf(alice)).isPresent();
        assertThat(alice.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(memberships.find(TenantId.DEFAULT, alice.id())).get()
                .extracting(SpaceMember::role).isEqualTo(UserRole.ADMIN);

        AuthSessionView login = service.login(new LoginCommand("default", "Alice", PASSWORD));
        Optional<AuthPrincipal> principal = service.authenticate(login.token());
        assertThat(principal).hasValueSatisfying(auth -> {
            assertThat(auth.username()).isEqualTo("alice");
            assertThat(auth.tenantId()).isEqualTo(TenantId.DEFAULT);
            assertThat(auth.role()).isEqualTo(UserRole.ADMIN);
            assertThat(auth.isSystem()).isFalse();
        });
    }

    @Test
    void setupRejectsSecondInitialization() {
        assertThatThrownBy(() -> service.setup(new SetupCommand("Bob", "Bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_ALREADY_INITIALIZED));
    }

    @Test
    void setupRejectsShortPassword() {
        assertThatThrownBy(() -> service.setup(new SetupCommand("Alice", "Alice", "short")))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void loginRejectsWrongPasswordWithGenericError() {
        assertThatThrownBy(() -> service.login(new LoginCommand("default", "alice", "wrong-pass")))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS);
                    assertThat(error.getMessage()).doesNotContain("存在");
                });
    }

    @Test
    void loginRejectsUnknownTenantAndUserWithSameError() {
        assertThatThrownBy(() -> service.login(new LoginCommand("missing", "alice", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS));
        assertThatThrownBy(() -> service.login(new LoginCommand("default", "nobody", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    @Test
    void loginLocksAccountAfterFiveFailuresThenExpires() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service.login(
                    new LoginCommand("default", "alice", "wrong-pass")))
                    .isInstanceOfSatisfying(DomainException.class, error ->
                            assertThat(error.code())
                                    .isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS));
        }
        assertThatThrownBy(() -> service.login(new LoginCommand("default", "alice", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_LOGIN_LOCKED));

        clock.advance(Duration.ofMinutes(6));
        assertThat(service.login(new LoginCommand("default", "alice", PASSWORD)).token())
                .isNotBlank();
    }

    @Test
    void authenticateRejectsExpiredRevokedAndUnknownTokens() {
        String token = service.login(new LoginCommand("default", "alice", PASSWORD)).token();

        clock.advance(TTL.plusSeconds(1));
        assertThat(service.authenticate(token)).isEmpty();

        String fresh = service.login(new LoginCommand("default", "alice", PASSWORD)).token();
        service.logout(fresh);
        assertThat(service.authenticate(fresh)).isEmpty();

        assertThat(service.authenticate("unknown")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate(" ")).isEmpty();
        assertThat(service.authenticate("x".repeat(513))).isEmpty();
    }

    @Test
    void disabledUserCannotLoginOrUseExistingToken() {
        String token = service.login(new LoginCommand("default", "alice", PASSWORD)).token();
        AppUser disabled = new AppUser(alice.id(), alice.username(), alice.displayName(),
                alice.passwordHash(), UserStatus.DISABLED, NOW, NOW);
        users.insert(disabled);

        assertThatThrownBy(() -> service.login(new LoginCommand("default", "alice", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS));
        assertThat(service.authenticate(token)).isEmpty();
    }

    @Test
    void meRequiresLoginAndDescribesTenant() {
        assertThatThrownBy(service::me)
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_REQUIRED));

        AuthContext.bind(new AuthPrincipal(TenantId.DEFAULT, alice.id(), alice.username(),
                alice.displayName(), UserRole.ADMIN));
        assertThat(service.me().tenantSlug()).isEqualTo(Tenant.DEFAULT_SLUG);
        assertThat(service.me().tenantName()).isEqualTo("默认空间");
        assertThat(service.me().tenantKind()).isEqualTo("DEFAULT");
        assertThat(service.me().role()).isEqualTo("ADMIN");
    }

    @Test
    void tokenHashIsStoredInsteadOfPlainToken() {
        String token = service.login(new LoginCommand("default", "alice", PASSWORD)).token();
        assertThat(tokens.findByHash(tokenCodec.hash(token))).isPresent();
        assertThat(tokens.findByHash(token)).isEmpty();
    }

    @Test
    void loginWithoutSpaceOffersDefaultAndPersonalSpaces() {
        assertThatThrownBy(() -> service.login(new LoginCommand(null, "Alice", PASSWORD)))
                .isInstanceOfSatisfying(TenantSelectionRequiredException.class, error ->
                        assertThat(error.choices()).extracting(TenantChoice::kind)
                                .containsExactly("DEFAULT", "PERSONAL"));
        assertThatThrownBy(() -> service.login(new LoginCommand(" ", "alice", PASSWORD)))
                .isInstanceOf(TenantSelectionRequiredException.class);
    }

    @Test
    void loginWithSpaceSkipsSelectionAndUsesSpaceRole() {
        Tenant team = createTenant("team-b", "团队 B");
        memberships.insert(SpaceMember.create(team.id(), alice.id(), UserRole.MEMBER, NOW));

        AuthSessionView session = service.login(new LoginCommand("team-b", "alice", PASSWORD));
        assertThat(session.user().tenantId()).isEqualTo(team.id().toString());
        assertThat(session.user().tenantName()).isEqualTo("团队 B");
        assertThat(session.user().role()).isEqualTo("MEMBER");
        assertThat(service.authenticate(session.token())).get()
                .extracting(AuthPrincipal::role).isEqualTo(UserRole.MEMBER);
    }

    @Test
    void switchingSpaceIssuesNewTokenAndRevokesOldOne() {
        Tenant team = createTenant("team-b", "团队 B");
        memberships.insert(SpaceMember.create(team.id(), alice.id(), UserRole.MEMBER, NOW));
        String original = service.login(new LoginCommand("default", "alice", PASSWORD)).token();

        AuthSessionView switched = service.switchSpace(original,
                new SwitchSpaceCommand("team-b"));

        assertThat(switched.user().tenantSlug()).isEqualTo("team-b");
        assertThat(service.authenticate(original)).isEmpty();
        assertThat(service.authenticate(switched.token())).get()
                .extracting(AuthPrincipal::tenantId).isEqualTo(team.id());
    }

    @Test
    void switchingToSpaceWithoutMembershipIsForbidden() {
        Tenant team = createTenant("team-b", "团队 B");
        String token = service.login(new LoginCommand("default", "alice", PASSWORD)).token();

        assertThatThrownBy(() -> service.switchSpace(token, new SwitchSpaceCommand("team-b")))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_FORBIDDEN));
        assertThat(service.authenticate(token)).isPresent();
    }

    @Test
    void archivedSpaceIsSkippedWhenLocatingAndWhenNamedExplicitly() {
        AppUser bob = createAccount("bob", PASSWORD);
        Tenant team = createTenant("team-b", "团队 B");
        memberships.insert(SpaceMember.create(team.id(), bob.id(), UserRole.MEMBER, NOW));
        memberships.insert(SpaceMember.create(TenantId.DEFAULT, bob.id(), UserRole.MEMBER, NOW));
        tenants.updateDeletion(team.id(), NOW);

        AuthSessionView session = service.login(new LoginCommand(null, "bob", PASSWORD));
        assertThat(session.user().tenantSlug()).isEqualTo(Tenant.DEFAULT_SLUG);
        assertThatThrownBy(() -> service.login(new LoginCommand("team-b", "bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    @Test
    void registerCreatesPersonalSpaceAndLogsIn() {
        AuthSessionView session = service.register(new RegisterCommand("Bob", "Bob", PASSWORD));

        assertThat(session.user().role()).isEqualTo("ADMIN");
        assertThat(session.user().tenantKind()).isEqualTo("PERSONAL");
        assertThat(session.user().tenantSlug()).startsWith("u-bob-");
        AppUser bob = users.findByUsername("bob").get(0);
        assertThat(personalSpaceOf(bob)).isPresent();
        assertThat(service.authenticate(session.token())).isPresent();
    }

    @Test
    void registerIsRejectedWhenDisabled() {
        AuthApplicationService closed = newService(false);

        assertThatThrownBy(() -> closed.register(new RegisterCommand("bob", "Bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code())
                                .isEqualTo(DomainErrorCode.AUTH_REGISTRATION_DISABLED));
    }

    @Test
    void registerRequiresSetupAndRejectsDuplicateUsername() {
        AuthApplicationService fresh = new AuthApplicationService(new InMemoryTenantRepository(),
                new InMemoryUserRepository(), new InMemoryMembershipRepository(),
                new InMemoryAuthTokenRepository(), new PrefixPasswordHasher(),
                new SequentialTokenCodec(), ids, clock, TTL, true);
        assertThatThrownBy(() -> fresh.register(new RegisterCommand("bob", "Bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_SETUP_REQUIRED));

        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "Alice", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.USER_ALREADY_EXISTS));
    }

    @Test
    void legacyAccountGetsPersonalSpaceOnNextLogin() {
        AppUser carol = createAccount("carol", PASSWORD);
        memberships.insert(SpaceMember.create(TenantId.DEFAULT, carol.id(), UserRole.MEMBER, NOW));
        assertThat(personalSpaceOf(carol)).isEmpty();

        service.login(new LoginCommand("default", "carol", PASSWORD));

        assertThat(personalSpaceOf(carol)).isPresent();
    }

    private AuthApplicationService newService(boolean registrationEnabled) {
        return new AuthApplicationService(tenants, users, memberships, tokens,
                new PrefixPasswordHasher(), tokenCodec, ids, clock, TTL, registrationEnabled);
    }

    private Tenant createTenant(String slug, String name) {
        Tenant tenant = Tenant.create(TenantId.newId(ids), slug, name, TenantKind.TEAM, NOW);
        tenants.insert(tenant);
        return tenant;
    }

    private AppUser createAccount(String username, String password) {
        AppUser user = AppUser.create(UserId.newId(ids), username, username,
                new PrefixPasswordHasher().hash(password), NOW);
        users.insert(user);
        return user;
    }

    private Optional<Tenant> personalSpaceOf(AppUser user) {
        List<SpaceMember> joined = memberships.findByUser(user.id());
        return joined.stream()
                .map(member -> tenants.findById(member.tenantId()))
                .flatMap(Optional::stream)
                .filter(Tenant::isPersonal)
                .findFirst();
    }
}
