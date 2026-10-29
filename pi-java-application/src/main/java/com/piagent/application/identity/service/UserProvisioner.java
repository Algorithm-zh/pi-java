package com.piagent.application.identity.service;

import com.piagent.application.identity.port.PasswordHasher;
import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.port.UserRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;

/**
 * 账号创建协作器：校验密码、检查重名、哈希密码并落库。
 */
final class UserProvisioner {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final UserRepository users;
    private final PasswordHasher passwords;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建协作器。
     *
     * @param users 账号仓储
     * @param passwords 密码哈希
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    UserProvisioner(UserRepository users, PasswordHasher passwords, IdGenerator idGenerator,
            Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 在租户内创建启用账号。
     *
     * @param tenantId 租户
     * @param username 登录名
     * @param displayName 展示名
     * @param password 明文密码
     * @param role 角色
     * @return 新账号
     */
    AppUser create(TenantId tenantId, String username, String displayName, String password,
            UserRole role) {
        String normalized = AppUser.normalizeUsername(username);
        if (users.findByUsername(tenantId, normalized).isPresent()) {
            throw new DomainException(DomainErrorCode.USER_ALREADY_EXISTS,
                    "用户名已被占用: " + normalized);
        }
        AppUser user = AppUser.create(UserId.newId(idGenerator), tenantId, normalized, displayName,
                passwords.hash(password), role, clock.instant());
        users.insert(user);
        return user;
    }

    /**
     * 校验密码长度。
     *
     * @param password 明文密码
     */
    static void requirePassword(String password) {
        Guard.require(password != null && password.length() >= MIN_PASSWORD_LENGTH
                        && password.length() <= MAX_PASSWORD_LENGTH,
                DomainErrorCode.INVALID_ARGUMENT, "密码长度必须在 8 到 128 之间");
    }
}
