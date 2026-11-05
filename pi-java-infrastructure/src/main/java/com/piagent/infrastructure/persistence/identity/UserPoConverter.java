package com.piagent.infrastructure.persistence.identity;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserStatus;

/**
 * `pi_app_user` 行与账号聚合之间的转换。
 */
public final class UserPoConverter {

    /**
     * 聚合转持久化对象。
     *
     * @param user 账号
     * @return 持久化对象
     */
    public UserPO toPo(AppUser user) {
        UserPO po = new UserPO();
        po.setId(user.id().toString());
        po.setUsername(user.username());
        po.setDisplayName(user.displayName());
        po.setPasswordHash(user.passwordHash());
        po.setStatus(user.status().name());
        po.setCreatedAt(user.createdAt());
        po.setUpdatedAt(user.updatedAt());
        return po;
    }

    /**
     * 持久化对象转聚合。
     *
     * @param po 持久化对象
     * @return 账号
     */
    public AppUser toDomain(UserPO po) {
        return new AppUser(UserId.parse(po.getId()), po.getUsername(), po.getDisplayName(),
                po.getPasswordHash(), UserStatus.valueOf(po.getStatus()),
                po.getCreatedAt(), po.getUpdatedAt());
    }
}
