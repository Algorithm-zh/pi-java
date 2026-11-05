package com.piagent.infrastructure.persistence.identity;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * `pi_app_user` 表 MyBatis 映射器。
 */
@Mapper
public interface UserMapper {

    /**
     * 插入账号。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_app_user
                (id, username, display_name, password_hash, status, created_at, updated_at)
            VALUES
                (#{id}, #{username}, #{displayName}, #{passwordHash}, #{status},
                 #{createdAt}, #{updatedAt})
            """)
    int insert(UserPO po);

    /**
     * 按标识查询。
     *
     * @param id 账号标识
     * @return 持久化对象
     */
    @Select("SELECT * FROM pi_app_user WHERE id = #{id}")
    UserPO findById(@Param("id") String id);

    /**
     * 按登录名查询。
     *
     * @param username 已规范化的登录名
     * @return 账号列表
     */
    @Select("""
            SELECT * FROM pi_app_user
            WHERE username = #{username}
            ORDER BY created_at ASC, id ASC
            """)
    List<UserPO> findByUsername(@Param("username") String username);

    /**
     * 账号总数。
     *
     * @return 总数
     */
    @Select("SELECT COUNT(*) FROM pi_app_user")
    long count();
}
