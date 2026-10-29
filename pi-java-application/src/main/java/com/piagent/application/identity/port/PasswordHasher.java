package com.piagent.application.identity.port;

/**
 * 密码哈希端口。
 *
 * <p>编码格式由适配器决定；应用层只保存与校验，不接触盐或迭代次数。</p>
 */
public interface PasswordHasher {

    /**
     * 计算密码哈希。
     *
     * @param plain 明文密码
     * @return 可持久化的编码
     */
    String hash(String plain);

    /**
     * 校验密码。
     *
     * @param plain 明文密码
     * @param encoded 已保存的编码
     * @return true 表示匹配
     */
    boolean matches(String plain, String encoded);
}
