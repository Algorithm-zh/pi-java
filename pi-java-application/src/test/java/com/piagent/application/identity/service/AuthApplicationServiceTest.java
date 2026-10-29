package com.piagent.application.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.command.LoginCommand;
import com.piagent.application.identity.command.RegisterCommand;
import com.piagent.application.identity.command.SetupCommand;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.readmodel.TenantChoice;
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
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.model.UserStatus;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 本地鉴权用例测试：初始化、登录、锁定、令牌过期与登出。
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
    private final InMemoryAuthTokenRepository tokens = new InMemoryAuthTokenRepository();
    private final MutableClock clock = new MutableClock(NOW);
    private final SequentialTokenCodec tokenCodec = new SequentialTokenCodec();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();

    private AuthApplicationService service;
    private AppUser alice;

    @BeforeEach
    void setUp() {
        service = new AuthApplicationService(tenants, users, tokens,
                new PrefixPasswordHasher(), tokenCodec, ids, clock, TTL, true);
        AuthSessionView session = service.setup(new SetupCommand("Alice", "Alice", PASSWORD));
        alice = users.findByUsername(TenantId.DEFAULT, "alice").orElseThrow(
                () -> new AssertionError("初始化后应存在管理员账号"));
        assertThat(session.user().username()).isEqualTo("alice");
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void setupCreatesDefaultTenantAndIssuesUsableToken() {
        assertThat(service.initialized()).isTrue();
        Tenant tenant = tenants.findById(TenantId.DEFAULT).orElseThrow();
        assertThat(tenant.slug()).isEqualTo(Tenant.DEFAULT_SLUG);
        assertThat(alice.role()).isEqualTo(UserRole.ADMIN);
        assertThat(alice.status()).isEqualTo(UserStatus.ACTIVE);

        AuthSessionView login = service.login(new LoginCommand(null, "Alice", PASSWORD));
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
        AuthApplicationService fresh = new AuthApplicationService(tenants, users, tokens,
                new PrefixPasswordHasher(), tokenCodec, ids, clock, TTL, true);
        assertThatThrownBy(() -> fresh.setup(new SetupCommand("Alice", "Alice", "short")))
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
        AppUser disabled = new AppUser(alice.id(), alice.tenantId(), alice.username(),
                alice.displayName(), alice.passwordHash(), UserRole.MEMBER, UserStatus.DISABLED,
                NOW, NOW);
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
                alice.displayName(), alice.role()));
        assertThat(service.me().tenantSlug()).isEqualTo(Tenant.DEFAULT_SLUG);
        assertThat(service.me().tenantName()).isEqualTo("默认空间");
        assertThat(service.me().role()).isEqualTo("ADMIN");
    }

    @Test
    void tokenHashIsStoredInsteadOfPlainToken() {
        String token = service.login(new LoginCommand("default", "alice", PASSWORD)).token();
        assertThat(tokens.findByHash(tokenCodec.hash(token))).isPresent();
        assertThat(tokens.findByHash(token)).isEmpty();
    }

    @Test
    void loginWithoutSpaceFindsAccountAutomatically() {
        AuthSessionView session = service.login(new LoginCommand(null, "Alice", PASSWORD));
        assertThat(session.user().tenantSlug()).isEqualTo(Tenant.DEFAULT_SLUG);
        assertThat(service.authenticate(session.token())).isPresent();
        assertThat(service.login(new LoginCommand(" ", "alice", PASSWORD)).user().username())
                .isEqualTo("alice");
    }

    @Test
    void sameUsernameInTwoSpacesRequiresSelectionThenLogsIntoChosenSpace() {
        Tenant team = createTenant("team-b", "团队 B");
        AppUser aliceInTeam = createMember(team, "alice", PASSWORD);

        assertThatThrownBy(() -> service.login(new LoginCommand(null, "alice", PASSWORD)))
                .isInstanceOfSatisfying(TenantSelectionRequiredException.class, error ->
                        assertThat(error.choices()).extracting(TenantChoice::slug)
                                .containsExactly("default", "team-b"));

        AuthSessionView chosen = service.login(new LoginCommand("team-b", "alice", PASSWORD));
        assertThat(chosen.user().tenantId()).isEqualTo(aliceInTeam.tenantId().toString());
        assertThat(chosen.user().tenantName()).isEqualTo("团队 B");
    }

    @Test
    void archivedSpaceIsSkippedWhenLocatingAndWhenNamedExplicitly() {
        Tenant team = createTenant("team-b", "团队 B");
        createMember(team, "bob", PASSWORD);
        tenants.updateDeletion(team.id(), NOW);

        assertThatThrownBy(() -> service.login(new LoginCommand(null, "bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS));
        assertThatThrownBy(() -> service.login(new LoginCommand("team-b", "bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    @Test
    void registerCreatesMemberInDefaultSpaceAndLogsIn() {
        AuthSessionView session = service.register(new RegisterCommand("Bob", "Bob", PASSWORD));

        assertThat(session.user().role()).isEqualTo("MEMBER");
        assertThat(session.user().tenantSlug()).isEqualTo(Tenant.DEFAULT_SLUG);
        AppUser bob = users.findByUsername(TenantId.DEFAULT, "bob").orElseThrow();
        assertThat(bob.role()).isEqualTo(UserRole.MEMBER);
        assertThat(service.authenticate(session.token())).isPresent();
    }

    @Test
    void registerIsRejectedWhenDisabled() {
        AuthApplicationService closed = new AuthApplicationService(tenants, users, tokens,
                new PrefixPasswordHasher(), tokenCodec, ids, clock, TTL, false);

        assertThatThrownBy(() -> closed.register(new RegisterCommand("bob", "Bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code())
                                .isEqualTo(DomainErrorCode.AUTH_REGISTRATION_DISABLED));
    }

    @Test
    void registerRequiresSetupAndRejectsDuplicateUsername() {
        AuthApplicationService fresh = new AuthApplicationService(new InMemoryTenantRepository(),
                new InMemoryUserRepository(), new InMemoryAuthTokenRepository(),
                new PrefixPasswordHasher(), new SequentialTokenCodec(), ids, clock, TTL, true);
        assertThatThrownBy(() -> fresh.register(new RegisterCommand("bob", "Bob", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.AUTH_SETUP_REQUIRED));

        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "Alice", PASSWORD)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.USER_ALREADY_EXISTS));
    }

    private Tenant createTenant(String slug, String name) {
        Tenant tenant = Tenant.create(TenantId.newId(ids), slug, name, NOW);
        tenants.insert(tenant);
        return tenant;
    }

    private AppUser createMember(Tenant tenant, String username, String password) {
        AppUser user = AppUser.create(UserId.newId(ids), tenant.id(), username, username,
                new PrefixPasswordHasher().hash(password), UserRole.MEMBER, NOW);
        users.insert(user);
        return user;
    }
}
