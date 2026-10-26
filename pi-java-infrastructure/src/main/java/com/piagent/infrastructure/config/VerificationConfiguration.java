package com.piagent.infrastructure.config;

import com.piagent.application.verify.VerificationOptions;
import com.piagent.domain.verify.port.VerificationRunner;
import com.piagent.infrastructure.verify.ShellVerificationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自动验证装配。
 *
 * <p>命令为空时服务仍会注册，只是 {@link VerificationOptions#enabled()} 为 false，
 * 避免应用层出现可选依赖；是否执行由配置决定。</p>
 */
@Configuration
public class VerificationConfiguration {

    /**
     * 验证命令执行器。
     *
     * @return shell 执行器
     */
    @Bean
    public VerificationRunner verificationRunner() {
        return new ShellVerificationRunner();
    }

    /**
     * 验证配置。
     *
     * @param command 验证命令；为空表示关闭
     * @param timeoutSeconds 超时秒数
     * @param maxOutputChars 输出上限
     * @param retryOnFailure 失败后是否回写一次
     * @return 配置
     */
    @Bean
    public VerificationOptions verificationOptions(
            @Value("${pi.verify.command:}") String command,
            @Value("${pi.verify.timeout-seconds:120}") int timeoutSeconds,
            @Value("${pi.verify.max-output-chars:6000}") int maxOutputChars,
            @Value("${pi.verify.retry-on-failure:true}") boolean retryOnFailure) {
        return new VerificationOptions(command, timeoutSeconds, maxOutputChars, retryOnFailure);
    }
}
