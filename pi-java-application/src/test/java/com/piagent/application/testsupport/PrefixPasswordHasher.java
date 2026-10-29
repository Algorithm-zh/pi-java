package com.piagent.application.testsupport;

import com.piagent.application.identity.port.PasswordHasher;

/**
 * 应用层测试用的可逆密码哈希，避免依赖基础设施模块的 PBKDF2 实现。
 */
public final class PrefixPasswordHasher implements PasswordHasher {

    private static final String PREFIX = "hash:";

    @Override
    public String hash(String plain) {
        return PREFIX + plain;
    }

    @Override
    public boolean matches(String plain, String encoded) {
        return plain != null && encoded != null && encoded.equals(hash(plain));
    }
}
