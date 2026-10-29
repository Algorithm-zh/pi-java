package com.piagent.infrastructure.config;

import com.piagent.application.identity.port.PasswordHasher;
import com.piagent.application.identity.port.TokenCodec;
import com.piagent.infrastructure.security.Pbkdf2PasswordHasher;
import com.piagent.infrastructure.security.Sha256TokenCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 鉴权适配器装配：密码哈希与令牌编码都只依赖 JDK。
 */
@Configuration
public class AuthConfiguration {

    /**
     * PBKDF2 密码哈希。
     *
     * @return 哈希器
     */
    @Bean
    public PasswordHasher passwordHasher() {
        return new Pbkdf2PasswordHasher();
    }

    /**
     * SHA-256 令牌编码。
     *
     * @return 编解码器
     */
    @Bean
    public TokenCodec tokenCodec() {
        return new Sha256TokenCodec();
    }
}
