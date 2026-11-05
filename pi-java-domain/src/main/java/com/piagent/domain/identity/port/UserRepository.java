package com.piagent.domain.identity.port;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.UserId;
import java.util.List;
import java.util.Optional;

/**
 * 全局账号仓储。
 */
public interface UserRepository {

    /**
     * 按标识查询。
     *
     * @param id 账号标识
     * @return 账号，不存在时为空
     */
    Optional<AppUser> findById(UserId id);

    /**
     * 按登录名查询；升级前不同空间可能存在同名账号，因此返回列表。
     *
     * @param username 已规范化的登录名
     * @return 账号列表
     */
    List<AppUser> findByUsername(String username);

    /**
     * 账号总数，用于判断是否需要首次初始化。
     *
     * @return 总数
     */
    long count();

    /**
     * 插入新账号。
     *
     * @param user 账号
     */
    void insert(AppUser user);
}
