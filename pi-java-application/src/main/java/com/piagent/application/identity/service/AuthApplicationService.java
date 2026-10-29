package com.piagent.application.identity.service;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.command.LoginCommand;
import com.piagent.application.identity.command.SetupCommand;
import com.piagent.application.identity.port.PasswordHasher;
import com.piagent.application.identity.port.TokenCodec;
import com.piagent.application.identity.readmodel.AuthSessionView;
import com.piagent.application.identity.readmodel.AuthUserView;
import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.AuthToken;
import com.piagent.domain.identity.model.AuthTokenId;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.port.AuthTokenRepository;
import com.piagent.domain.identity.port.TenantRepository;
import com.piagent.domain.identity.port.UserRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地鉴权用例：首次初始化、登录、令牌校验、登出与当前账号查询。
 *
 * <p>密码只以 PBKDF2 编码保存，令牌只保存 SHA-256 哈希。认证失败统一返回
 * “用户名或密码不正确”，避免枚举账号；租户与账号管理见
 * {@link IdentityAdminService}。</p>
 */
@Service
public class AuthApplicationService {

    private static final int MAX_TOKEN_LENGTH = 512;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final System.Logger LOG =
            System.getLogger(AuthApplicationService.class.getName());

    private final TenantRepository tenants;
    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final PasswordHasher passwords;
    private final TokenCodec tokenCodec;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final Duration tokenTtl;
    private final LoginThrottle throttle = new LoginThrottle();
    private final UserProvisioner provisioner;
    private final String dummyHash;

    /**
     * 创建鉴权服务。
     *
     * @param tenants 租户仓储
     * @param users 账号仓储
     * @param tokens 令牌仓储
     * @param passwords 密码哈希
     * @param tokenCodec 令牌编码
     * @param idGenerator 标识生成器
     * @param clock 时钟
     * @param tokenTtl 令牌有效期
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AuthApplicationService(TenantRepository tenants, UserRepository users,
            AuthTokenRepository tokens, PasswordHasher passwords, TokenCodec tokenCodec,
            IdGenerator idGenerator, Clock clock,
            @Value("${pi.auth.token-ttl:7d}") Duration tokenTtl) {
        this.tenants = tenants;
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.tokenCodec = tokenCodec;
        this.idGenerator = idGenerator;
        this.clock = clock;
        Guard.require(tokenTtl != null && !tokenTtl.isNegative() && !tokenTtl.isZero(),
                DomainErrorCode.INVALID_ARGUMENT, "令牌有效期必须大于 0");
        this.tokenTtl = tokenTtl;
        this.provisioner = new UserProvisioner(users, passwords, idGenerator, clock);
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
     * 创建默认租户的管理员并直接登录。
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
        AppUser admin = provisioner.create(tenant.id(), command.username(), command.displayName(),
                command.password(), UserRole.ADMIN);
        return issue(tenant, admin);
    }

    /**
     * 登录并签发令牌。
     *
     * @param command 登录命令
     * @return 登录会话
     */
    @Transactional
    public AuthSessionView login(LoginCommand command) {
        String username = AppUser.normalizeUsername(command.username());
        String slug = slugOf(command.tenantSlug());
        String key = slug + "|" + username;
        Instant now = clock.instant();
        if (throttle.isLocked(key, now)) {
            throw new DomainException(DomainErrorCode.AUTH_LOGIN_LOCKED,
                    "登录失败次数过多，请稍后再试");
        }
        Tenant tenant = tenants.findBySlug(slug).orElse(null);
        AppUser user = tenant == null ? null
                : users.findByUsername(tenant.id(), username).orElse(null);
        String hash = user == null ? dummyHash : user.passwordHash();
        boolean matched = passwords.matches(command.password(), hash);
        if (user == null || !user.isActive() || !matched) {
            throttle.recordFailure(key, now);
            throw new DomainException(DomainErrorCode.AUTH_INVALID_CREDENTIALS,
                    "用户名或密码不正确");
        }
        throttle.reset(key);
        return issue(tenant, user);
    }

    /**
     * 校验令牌并返回主体。
     *
     * @param raw 令牌明文
     * @return 主体；令牌无效、过期或账号停用时为空
     */
    @Transactional
    public Optional<AuthPrincipal> authenticate(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        AuthToken token = tokens.findByHash(tokenCodec.hash(raw.strip())).orElse(null);
        if (token == null || !token.isUsableAt(now)) {
            return Optional.empty();
        }
        AppUser user = users.findById(token.userId()).orElse(null);
        if (user == null || !user.isActive() || !user.tenantId().equals(token.tenantId())) {
            return Optional.empty();
        }
        touch(token, now);
        return Optional.of(new AuthPrincipal(token.tenantId(), user.id(), user.username(),
                user.displayName(), user.role()));
    }

    /**
     * 吊销令牌；令牌不存在时静默返回。
     *
     * @param raw 令牌明文
     */
    @Transactional
    public void logout(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_TOKEN_LENGTH) {
            return;
        }
        tokens.findByHash(tokenCodec.hash(raw.strip()))
                .ifPresent(token -> tokens.revoke(token.id(), clock.instant()));
    }

    /**
     * 当前登录账号与租户。
     *
     * @return 账号信息
     */
    @Transactional(readOnly = true)
    public AuthUserView me() {
        AuthPrincipal principal = AuthContext.requirePrincipal();
        Tenant tenant = requireTenant(principal.tenantId());
        return new AuthUserView(principal.userId() == null ? "" : principal.userId().toString(),
                principal.username(), principal.displayName(), principal.role().name(),
                tenant.id().toString(), tenant.slug(), tenant.name());
    }

    private AuthSessionView issue(Tenant tenant, AppUser user) {
        String raw = tokenCodec.newToken();
        Instant now = clock.instant();
        AuthToken token = AuthToken.issue(AuthTokenId.newId(idGenerator), tenant.id(), user.id(),
                tokenCodec.hash(raw), now, now.plus(tokenTtl));
        tokens.insert(token);
        return new AuthSessionView(raw, AuthUserView.of(user, tenant));
    }

    private void touch(AuthToken token, Instant now) {
        if (token.lastUsedAt() != null && now.isBefore(token.lastUsedAt().plus(TOUCH_INTERVAL))) {
            return;
        }
        try {
            tokens.touch(token.id(), now);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "更新令牌使用时间失败: " + token.id(), error);
        }
    }

    private String slugOf(String tenantSlug) {
        return tenantSlug == null || tenantSlug.isBlank()
                ? Tenant.DEFAULT_SLUG
                : tenantSlug.strip().toLowerCase(Locale.ROOT);
    }

    private Tenant createDefaultTenant() {
        Tenant tenant = Tenant.create(TenantId.DEFAULT, Tenant.DEFAULT_SLUG, "默认空间",
                clock.instant());
        tenants.insert(tenant);
        return tenant;
    }

    private Tenant requireTenant(TenantId tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> new DomainException(
                DomainErrorCode.TENANT_NOT_FOUND, "租户不存在: " + tenantId));
    }
}
