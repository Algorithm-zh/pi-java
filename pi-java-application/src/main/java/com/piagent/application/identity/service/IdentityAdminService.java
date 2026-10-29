package com.piagent.application.identity.service;

import com.piagent.application.identity.AuthContext;
import com.piagent.application.identity.AuthPrincipal;
import com.piagent.application.identity.command.CreateTenantCommand;
import com.piagent.application.identity.command.CreateUserCommand;
import com.piagent.application.identity.port.PasswordHasher;
import com.piagent.application.identity.readmodel.TenantSummary;
import com.piagent.application.identity.readmodel.UserSummary;
import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.port.TenantRepository;
import com.piagent.domain.identity.port.UserRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户与账号管理用例；全部操作要求当前用户是管理员。
 */
@Service
public class IdentityAdminService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final UserProvisioner provisioner;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建管理服务。
     *
     * @param tenants 租户仓储
     * @param users 账号仓储
     * @param passwords 密码哈希
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    public IdentityAdminService(TenantRepository tenants, UserRepository users,
            PasswordHasher passwords, IdGenerator idGenerator, Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.provisioner = new UserProvisioner(users, passwords, idGenerator, clock);
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 查询全部租户。
     *
     * @return 租户列表
     */
    @Transactional(readOnly = true)
    public List<TenantSummary> listTenants() {
        requireAdmin();
        return tenants.findAll().stream().map(TenantSummary::of).toList();
    }

    /**
     * 创建租户及其首个管理员。
     *
     * @param command 创建命令
     * @return 新租户
     */
    @Transactional
    public TenantSummary createTenant(CreateTenantCommand command) {
        requireAdmin();
        UserProvisioner.requirePassword(command.password());
        Tenant tenant = Tenant.create(TenantId.newId(idGenerator), command.slug(), command.name(),
                clock.instant());
        if (tenants.findBySlug(tenant.slug()).isPresent()) {
            throw new DomainException(DomainErrorCode.TENANT_ALREADY_EXISTS,
                    "租户短名已被占用: " + tenant.slug());
        }
        tenants.insert(tenant);
        provisioner.create(tenant.id(), command.username(), command.displayName(),
                command.password(), UserRole.ADMIN);
        return TenantSummary.of(tenant);
    }

    /**
     * 查询当前租户的账号。
     *
     * @return 账号列表
     */
    @Transactional(readOnly = true)
    public List<UserSummary> listUsers() {
        requireAdmin();
        return users.findByTenant(AuthContext.tenantId()).stream().map(UserSummary::of).toList();
    }

    /**
     * 在当前租户内创建账号。
     *
     * @param command 创建命令
     * @return 新账号
     */
    @Transactional
    public UserSummary createUser(CreateUserCommand command) {
        requireAdmin();
        UserProvisioner.requirePassword(command.password());
        Guard.notNull(command.role(), DomainErrorCode.INVALID_ARGUMENT, "角色不能为空");
        AppUser user = provisioner.create(AuthContext.tenantId(), command.username(),
                command.displayName(), command.password(), command.role());
        return UserSummary.of(user);
    }

    private void requireAdmin() {
        AuthPrincipal principal = AuthContext.requirePrincipal();
        if (!principal.isAdmin()) {
            throw new DomainException(DomainErrorCode.AUTH_FORBIDDEN, "只有管理员可以执行该操作");
        }
    }
}
