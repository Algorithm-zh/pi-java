package com.piagent.infrastructure.security;

import com.piagent.application.identity.port.TokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 访问令牌编码：32 字节随机数 + SHA-256 哈希。
 *
 * <p>明文使用 URL 安全 Base64（无填充），方便放入 Cookie 与 Authorization 头；
 * 持久化只保存十六进制哈希，数据库泄露无法直接还原令牌。</p>
 */
public final class Sha256TokenCodec implements TokenCodec {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 缺少 SHA-256", error);
        }
    }
}
