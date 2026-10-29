package com.piagent.application.identity.port;

/**
 * 访问令牌编码端口。
 */
public interface TokenCodec {

    /**
     * 生成新的不可预测令牌明文。
     *
     * @return 令牌明文
     */
    String newToken();

    /**
     * 计算令牌哈希，用于持久化与查询。
     *
     * @param token 令牌明文
     * @return 十六进制哈希
     */
    String hash(String token);
}
