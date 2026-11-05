package com.piagent.application.identity.service;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.command.CreateTenantCommand;
import com.piagent.application.identity.command.CreateUserCommand;
import com.piagent.application.identity.command.InviteMemberCommand;
import com.piagent.application.identity.port.PasswordHasher;
import com.piagent.application.identity.readmodel.AccountSummary;
import com.piagent.application.identity.readmodel.TenantSummary;
import com.piagent.application.identity.readmodel.UserSummary;
import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.SpaceMember;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.TenantKind;
import com.piagent.domain.identity.model.UserId;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 空间与成员管理用例。
 *
 * <p>任何人都可以创建团队空间并成为管理员；空间列表只显示自己加入的空间。
 * 管理员可以在本空间创建新账号或邀请已有账号加入。空间删除是软删除：只写
 * 归档时间并吊销该空间全部令牌，数据保留且可恢复；默认空间与个人空间不能删除，
 * 当前所在空间也不能删除。</p>
 */
@Service
public class IdentityAdminService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final AuthTokenRepository tokens;
    private final UserProvisioner provisioner;
    private final PersonalSpaceService personalSpaces;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建管理服务。
     *
     * @param tenants 空间仓储
     * @param users 账号仓储
     * @param memberships 成员仓储
     * @param tokens 令牌仓储
     * @param passwords 密码哈希
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IdentityAdminService(TenantRepository tenants, UserRepository users,
            MembershipRepository memberships, AuthTokenRepository tokens,
            PasswordHasher passwords, IdGenerator idGenerator, Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
        this.tokens = tokens;
        this.provisioner = new UserProvisioner(users, passwords, idGenerator, clock);
        this.personalSpaces = new PersonalSpaceService(tenants, memberships, idGenerator, clock);
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 查询当前账号加入的空间，包含已归档空间。
     *
     * @return 空间列表
     */
    @Transactional(readOnly = true)
    public List<TenantSummary> listTenants() {
        AuthPrincipal principal = requireUser();
        List<TenantSummary> result = new ArrayList<>();
        for (SpaceMember membership : memberships.findByUser(principal.userId())) {
            tenants.findById(membership.tenantId())
                    .ifPresent(tenant -> result.add(TenantSummary.of(tenant, membership.role())));
        }
        result.sort(Comparator.comparing(TenantSummary::id));
        return result;
    }

    /**
     * 创建团队空间，创建者自动成为管理员。
     *
     * @param command 创建命令
     * @return 新空间
     */
    @Transactional
    public TenantSummary createTenant(CreateTenantCommand command) {
        AuthPrincipal principal = requireUser();
        Tenant tenant = Tenant.create(TenantId.newId(idGenerator), command.slug(), command.name(),
                TenantKind.TEAM, clock.instant());
        if (tenants.findBySlug(tenant.slug()).isPresent()) {
            throw new DomainException(DomainErrorCode.TENANT_ALREADY_EXISTS,
                    "空间短名已被占用: " + tenant.slug());
        }
        tenants.insert(tenant);
        SpaceMember membership = SpaceMember.create(tenant.id(), principal.userId(),
                UserRole.ADMIN, clock.instant());
        memberships.insert(membership);
        return TenantSummary.of(tenant, membership.role());
    }

    /**
     * 归档（软删除）空间。
     *
     * @param tenantId 空间标识
     * @return 归档后的空间
     */
    @Transactional
    public TenantSummary archiveTenant(String tenantId) {
        Tenant tenant = requireTenant(tenantId);
        SpaceMember membership = requireAdmin(tenant.id());
        if (tenant.isDefault()) {
            throw new DomainException(DomainErrorCode.TENANT_PROTECTED, "默认空间不能删除");
        }
        if (tenant.isPersonal()) {
            throw new DomainException(DomainErrorCode.TENANT_PROTECTED, "个人空间不能删除");
        }
        if (tenant.id().equals(AuthContext.tenantId())) {
            throw new DomainException(DomainErrorCode.TENANT_PROTECTED,
                    "不能删除当前所在的空间，请先切换到其他空间再操作");
        }
        if (tenant.isArchived()) {
            return TenantSummary.of(tenant, membership.role());
        }
        Instant now = clock.instant();
        tenants.updateDeletion(tenant.id(), now);
        tokens.revokeAllForTenant(tenant.id(), now);
        return TenantSummary.of(tenant.archive(now), membership.role());
    }

    /**
     * 恢复归档空间。
     *
     * @param tenantId 空间标识
     * @return 恢复后的空间
     */
    @Transactional
    public TenantSummary restoreTenant(String tenantId) {
        Tenant tenant = requireTenant(tenantId);
        SpaceMember membership = requireAdmin(tenant.id());
        if (!tenant.isArchived()) {
            return TenantSummary.of(tenant, membership.role());
        }
        tenants.updateDeletion(tenant.id(), null);
        return TenantSummary.of(tenant.restore(), membership.role());
    }

    /**
     * 查询当前空间的成员。
     *
     * @return 成员列表
     */
    @Transactional(readOnly = true)
    public List<UserSummary> listUsers() {
        TenantId tenantId = AuthContext.tenantId();
        requireAdmin(tenantId);
        List<UserSummary> result = new ArrayList<>();
        for (SpaceMember membership : memberships.findByTenant(tenantId)) {
            users.findById(membership.userId())
                    .ifPresent(user -> result.add(UserSummary.of(user, membership.role())));
        }
        return result;
    }

    /**
     * 在当前空间内创建新账号，并自动为其创建个人空间。
     *
     * @param command 创建命令
     * @return 新账号
     */
    @Transactional
    public UserSummary createUser(CreateUserCommand command) {
        TenantId tenantId = AuthContext.tenantId();
        requireAdmin(tenantId);
        UserProvisioner.requirePassword(command.password());
        Guard.notNull(command.role(), DomainErrorCode.INVALID_ARGUMENT, "角色不能为空");
        AppUser user = provisioner.create(command.username(), command.displayName(),
                command.password());
        memberships.insert(SpaceMember.create(tenantId, user.id(), command.role(),
                clock.instant()));
        personalSpaces.ensurePersonal(user);
        return UserSummary.of(user, command.role());
    }

    /**
     * 查找可邀请的已有账号：按登录名精确匹配，排除当前空间成员。
     *
     * @param username 登录名
     * @return 账号列表
     */
    @Transactional(readOnly = true)
    public List<AccountSummary> findInvitable(String username) {
        TenantId tenantId = AuthContext.tenantId();
        requireAdmin(tenantId);
        Set<UserId> existing = new HashSet<>();
        for (SpaceMember member : memberships.findByTenant(tenantId)) {
            existing.add(member.userId());
        }
        return users.findByUsername(AppUser.normalizeUsername(username)).stream()
                .filter(AppUser::isActive)
                .filter(user -> !existing.contains(user.id()))
                .map(AccountSummary::of)
                .toList();
    }

    /**
     * 把已有账号邀请进指定空间。
     *
     * @param tenantId 空间标识
     * @param command 邀请命令
     * @return 新成员
     */
    @Transactional
    public UserSummary inviteMember(String tenantId, InviteMemberCommand command) {
        Tenant tenant = requireTenant(tenantId);
        requireAdmin(tenant.id());
        if (tenant.isArchived()) {
            throw new DomainException(DomainErrorCode.TENANT_PROTECTED,
                    "空间已归档，不能添加成员");
        }
        Guard.notNull(command.role(), DomainErrorCode.INVALID_ARGUMENT, "角色不能为空");
        UserId userId;
        try {
            userId = UserId.parse(command.userId());
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.USER_NOT_FOUND,
                    "账号不存在: " + command.userId());
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new DomainException(
                DomainErrorCode.USER_NOT_FOUND, "账号不存在: " + command.userId()));
        if (!user.isActive()) {
            throw new DomainException(DomainErrorCode.AUTH_FORBIDDEN, "该账号已停用");
        }
        if (memberships.find(tenant.id(), user.id()).isPresent()) {
            throw new DomainException(DomainErrorCode.USER_ALREADY_EXISTS,
                    "该账号已经是这个空间的成员");
        }
        SpaceMember membership = SpaceMember.create(tenant.id(), user.id(), command.role(),
                clock.instant());
        memberships.insert(membership);
        personalSpaces.ensurePersonal(user);
        return UserSummary.of(user, membership.role());
    }

    private AuthPrincipal requireUser() {
        AuthPrincipal principal = AuthContext.requirePrincipal();
        if (principal.isSystem() || principal.userId() == null) {
            throw new DomainException(DomainErrorCode.AUTH_REQUIRED, "请先登录");
        }
        return principal;
    }

    private SpaceMember requireAdmin(TenantId tenantId) {
        AuthPrincipal principal = requireUser();
        return memberships.find(tenantId, principal.userId())
                .filter(SpaceMember::isAdmin)
                .orElseThrow(() -> new DomainException(DomainErrorCode.AUTH_FORBIDDEN,
                        "只有空间管理员可以执行该操作"));
    }

    private Tenant requireTenant(String tenantId) {
        TenantId id;
        try {
            id = TenantId.parse(tenantId);
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.TENANT_NOT_FOUND, "空间不存在: " + tenantId);
        }
        return tenants.findById(id).orElseThrow(() -> new DomainException(
                DomainErrorCode.TENANT_NOT_FOUND, "空间不存在: " + tenantId));
    }
}
