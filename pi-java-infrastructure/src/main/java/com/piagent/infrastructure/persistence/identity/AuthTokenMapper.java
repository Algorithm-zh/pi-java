package com.piagent.infrastructure.persistence.identity;

import java.time.Instant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `pi_auth_token` 表 MyBatis 映射器。
 */
@Mapper
public interface AuthTokenMapper {

    /**
     * 插入令牌记录。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_auth_token
                (id, tenant_id, user_id, token_hash, issued_at, expires_at, last_used_at, revoked_at)
            VALUES
                (#{id}, #{tenantId}, #{userId}, #{tokenHash}, #{issuedAt}, #{expiresAt},
                 #{lastUsedAt}, #{revokedAt})
            """)
    int insert(AuthTokenPO po);

    /**
     * 按哈希查询令牌。
     *
     * @param tokenHash SHA-256 十六进制哈希
     * @return 持久化对象
     */
    @Select("SELECT * FROM pi_auth_token WHERE token_hash = #{tokenHash}")
    AuthTokenPO findByHash(@Param("tokenHash") String tokenHash);

    /**
     * 更新最近使用时间。
     *
     * @param id 令牌标识
     * @param usedAt 使用时间
     * @return 影响行数
     */
    @Update("UPDATE pi_auth_token SET last_used_at = #{usedAt} WHERE id = #{id}")
    int touch(@Param("id") String id, @Param("usedAt") Instant usedAt);

    /**
     * 吊销单个令牌。
     *
     * @param id 令牌标识
     * @param now 吊销时间
     * @return 影响行数
     */
    @Update("""
            UPDATE pi_auth_token SET revoked_at = #{now}
            WHERE id = #{id} AND revoked_at IS NULL
            """)
    int revoke(@Param("id") String id, @Param("now") Instant now);

    /**
     * 吊销账号的全部令牌。
     *
     * @param userId 账号标识
     * @param now 吊销时间
     * @return 影响行数
     */
    @Update("""
            UPDATE pi_auth_token SET revoked_at = #{now}
            WHERE user_id = #{userId} AND revoked_at IS NULL
            """)
    int revokeAllForUser(@Param("userId") String userId, @Param("now") Instant now);

    /**
     * 删除过期令牌记录。
     *
     * @param now 当前时间
     * @return 删除行数
     */
    @Delete("DELETE FROM pi_auth_token WHERE expires_at < #{now}")
    int deleteExpired(@Param("now") Instant now);
}
