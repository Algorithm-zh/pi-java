package com.piagent.application.identity.service;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.command.LoginCommand;
import com.piagent.application.identity.command.RegisterCommand;
import com.piagent.application.identity.command.SetupCommand;
import com.piagent.application.identity.command.SwitchSpaceCommand;
import com.piagent.application.identity.port.PasswordHasher;
import com.piagent.application.identity.port.TokenCodec;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.readmodel.AuthUserView;
import com.piagent.application.identity.readmodel.TenantChoice;
import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.AuthToken;
import com.piagent.domain.identity.model.SpaceMember;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.TenantKind;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.port.AuthTokenRepository;
import com.piagent.domain.identity.port.MembershipRepository;
import com.piagent.domain.identity.port.TenantRepository;
import com.piagent.domain.identity.port.UserRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地鉴权用例：首次初始化、登录、空间切换、令牌校验、登出与当前账号查询。
 * 账号是全局身份，认证失败统一返回“用户名或密码不正确”以避免枚举账号；
 * 令牌细节见 {@link SessionTokens}，空间与成员管理见 {@link IdentityAdminService}。
 */
@Service
public class AuthApplicationService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final PasswordHasher passwords;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final SessionTokens sessionTokens;
    private final LoginThrottle throttle = new LoginThrottle();
    private final UserProvisioner provisioner;
    private final AccountLocator accounts;
    private final PersonalSpaceService personalSpaces;
    private final boolean registrationEnabled;
    private final String dummyHash;

    /**
     * 创建鉴权服务。
     *
     * @param tenants 空间仓储
     * @param users 账号仓储
     * @param memberships 成员仓储
     * @param tokens 令牌仓储
     * @param passwords 密码哈希
     * @param tokenCodec 令牌编码
     * @param idGenerator 标识生成器
     * @param clock 时钟
     * @param tokenTtl 令牌有效期
     * @param registrationEnabled 是否开放自助注册
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AuthApplicationService(TenantRepository tenants, UserRepository users,
            MembershipRepository memberships, AuthTokenRepository tokens, PasswordHasher passwords,
            TokenCodec tokenCodec, IdGenerator idGenerator, Clock clock,
            @Value("${pi.auth.token-ttl:7d}") Duration tokenTtl,
            @Value("${pi.auth.registration-enabled:true}") boolean registrationEnabled) {
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
        this.passwords = passwords;
        this.idGenerator = idGenerator;
        this.clock = clock;
        Guard.require(tokenTtl != null && !tokenTtl.isNegative() && !tokenTtl.isZero(),
                DomainErrorCode.INVALID_ARGUMENT, "令牌有效期必须大于 0");
        this.sessionTokens = new SessionTokens(tokens, tokenCodec, idGenerator, clock, tokenTtl);
        this.provisioner = new UserProvisioner(users, passwords, idGenerator, clock);
        this.accounts = new AccountLocator(tenants, memberships, users);
        this.personalSpaces = new PersonalSpaceService(tenants, memberships, idGenerator, clock);
        this.registrationEnabled = registrationEnabled;
        this.dummyHash = passwords.hash("pi-java-timing-equalizer");
    }

    /**
     * 是否已完成首次初始化。
     *
     * @return true 表示至少存在一个账号
     */
    @Transactional(readOnly = true)
    public boolean initialized() {
        return users.count() > 0;
    }

    /**
     * 创建默认空间的管理员并直接登录；管理员同时获得个人空间。
     *
     * @param command 初始化命令
     * @return 登录会话
     */
    @Transactional
    public synchronized AuthSessionView setup(SetupCommand command) {
        UserProvisioner.requirePassword(command.password());
        if (users.count() > 0) {
            throw new DomainException(DomainErrorCode.AUTH_ALREADY_INITIALIZED,
                    "服务已初始化，请直接登录");
        }
        Tenant tenant = tenants.findById(TenantId.DEFAULT).orElseGet(this::createDefaultTenant);
        AppUser admin = provisioner.create(command.username(), command.displayName(),
                command.password());
        SpaceMember membership = SpaceMember.create(tenant.id(), admin.id(), UserRole.ADMIN,
                clock.instant());
        memberships.insert(membership);
        personalSpaces.ensurePersonal(admin);
        return sessionTokens.issue(tenant, admin, membership.role());
    }

    /**
     * 登录并签发令牌；账号加入多个空间时抛出
     * {@link TenantSelectionRequiredException} 让前端点选，老账号自动补建个人空间。
     *
     * @param command 登录命令
     * @return 登录会话
     */
    @Transactional
    public AuthSessionView login(LoginCommand command) {
        String username = AppUser.normalizeUsername(command.username());
        String slug = explicitSlug(command.tenantSlug());
        String key = (slug == null ? "*" : slug) + "|" + username;
        Instant now = clock.instant();
        if (throttle.isLocked(key, now)) {
            throw new DomainException(DomainErrorCode.AUTH_LOGIN_LOCKED,
                    "登录失败次数过多，请稍后再试");
        }
        List<AccountLocator.Candidate> candidates = accounts.candidates(username, slug);
        List<AccountLocator.Candidate> matched = candidates.stream()
                .filter(candidate -> passwords.matches(command.password(),
                        candidate.user().passwordHash()))
                .filter(candidate -> candidate.user().isActive())
                .toList();
        if (matched.isEmpty()) {
            if (candidates.isEmpty()) {
                // 账号或成员关系不存在时也做一次哈希校验，让耗时与密码错误一致。
                passwords.matches(command.password(), dummyHash);
            }
            throttle.recordFailure(key, now);
            throw new DomainException(DomainErrorCode.AUTH_INVALID_CREDENTIALS,
                    "用户名或密码不正确");
        }
        if (matched.size() > 1) {
            throw new TenantSelectionRequiredException(matched.stream()
                    .map(candidate -> new TenantChoice(candidate.tenant().slug(),
                            candidate.tenant().name(), candidate.tenant().kind().name()))
                    .toList());
        }
        AccountLocator.Candidate hit = matched.get(0);
        throttle.reset(key);
        personalSpaces.ensurePersonal(hit.user());
        return sessionTokens.issue(hit.tenant(), hit.user(), hit.membership().role());
    }

    /**
     * 自助注册：创建全局账号与个人空间并直接登录。
     *
     * @param command 注册命令
     * @return 登录会话
     */
    @Transactional
    public AuthSessionView register(RegisterCommand command) {
        if (!registrationEnabled) {
            throw new DomainException(DomainErrorCode.AUTH_REGISTRATION_DISABLED,
                    "当前未开放自助注册，请联系管理员开通账号");
        }
        if (users.count() == 0) {
            throw new DomainException(DomainErrorCode.AUTH_SETUP_REQUIRED,
                    "服务尚未初始化，请先创建管理员");
        }
        UserProvisioner.requirePassword(command.password());
        AppUser user = provisioner.create(command.username(), command.displayName(),
                command.password());
        Tenant personal = personalSpaces.ensurePersonal(user);
        return sessionTokens.issue(personal, user, UserRole.ADMIN);
    }

    /**
     * 切换到当前账号已加入的另一个空间，并吊销原令牌。
     *
     * @param rawToken 当前令牌明文
     * @param command 切换命令
     * @return 新登录会话
     */
    @Transactional
    public AuthSessionView switchSpace(String rawToken, SwitchSpaceCommand command) {
        AuthPrincipal principal = authenticate(rawToken)
                .filter(existing -> !existing.isSystem())
                .orElseThrow(() -> new DomainException(DomainErrorCode.AUTH_REQUIRED,
                        "请先登录"));
        String slug = explicitSlug(command.tenantSlug());
        if (slug == null) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "空间短名不能为空");
        }
        Tenant tenant = tenants.findBySlug(slug)
                .filter(existing -> !existing.isArchived())
                .orElseThrow(() -> new DomainException(DomainErrorCode.TENANT_NOT_FOUND,
                        "空间不存在: " + slug));
        SpaceMember membership = memberships.find(tenant.id(), principal.userId())
                .orElseThrow(() -> new DomainException(DomainErrorCode.AUTH_FORBIDDEN,
                        "你不在这个空间中"));
        AuthSessionView session = sessionTokens.issue(tenant, requireUser(principal),
                membership.role());
        sessionTokens.revoke(rawToken);
        return session;
    }

    /**
     * 校验令牌并返回主体。
     *
     * @param raw 令牌明文
     * @return 主体；令牌无效、过期、账号停用或成员关系不存在时为空
     */
    @Transactional
    public Optional<AuthPrincipal> authenticate(String raw) {
        AuthToken token = sessionTokens.resolve(raw).orElse(null);
        if (token == null) {
            return Optional.empty();
        }
        AppUser user = users.findById(token.userId()).orElse(null);
        if (user == null || !user.isActive()) {
            return Optional.empty();
        }
        SpaceMember membership = memberships.find(token.tenantId(), user.id()).orElse(null);
        Tenant tenant = tenants.findById(token.tenantId()).orElse(null);
        if (membership == null || tenant == null || tenant.isArchived()) {
            return Optional.empty();
        }
        return Optional.of(new AuthPrincipal(token.tenantId(), user.id(), user.username(),
                user.displayName(), membership.role()));
    }

    /**
     * 吊销令牌；令牌不存在时静默返回。
     *
     * @param raw 令牌明文
     */
    @Transactional
    public void logout(String raw) {
        sessionTokens.revoke(raw);
    }

    /**
     * 当前登录账号与空间。
     *
     * @return 账号信息
     */
    @Transactional(readOnly = true)
    public AuthUserView me() {
        AuthPrincipal principal = AuthContext.requirePrincipal();
        if (principal.isSystem() || principal.userId() == null) {
            throw new DomainException(DomainErrorCode.AUTH_REQUIRED, "请先登录");
        }
        Tenant tenant = requireTenant(principal.tenantId());
        return AuthUserView.of(requireUser(principal), tenant, principal.role());
    }

    private AppUser requireUser(AuthPrincipal principal) {
        return users.findById(principal.userId()).orElseThrow(() -> new DomainException(
                DomainErrorCode.AUTH_REQUIRED, "请先登录"));
    }

    private String explicitSlug(String tenantSlug) {
        return tenantSlug == null || tenantSlug.isBlank()
                ? null
                : tenantSlug.strip().toLowerCase(Locale.ROOT);
    }

    private Tenant createDefaultTenant() {
        Tenant tenant = Tenant.create(TenantId.DEFAULT, Tenant.DEFAULT_SLUG, "默认空间",
                TenantKind.DEFAULT, clock.instant());
        tenants.insert(tenant);
        return tenant;
    }

    private Tenant requireTenant(TenantId tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> new DomainException(
                DomainErrorCode.TENANT_NOT_FOUND, "空间不存在: " + tenantId));
    }
}
