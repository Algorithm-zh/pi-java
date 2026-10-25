package com.piagent.infrastructure.config;

import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.infrastructure.git.CliGitWorkspace;
import com.piagent.infrastructure.git.DisabledWorkspaceGit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Git 检查点装配。
 *
 * <p>关闭时仍注册空实现，保证应用层依赖稳定；是否真正启用由配置决定。</p>
 */
@Configuration
@EnableConfigurationProperties(GitSettings.class)
public class GitConfiguration {

    /**
     * 创建工作区 Git 适配器。
     *
     * @param settings Git 配置
     * @return 适配器或空实现
     */
    @Bean
    public WorkspaceGit workspaceGit(GitSettings settings) {
        return settings.isEnabled() ? new CliGitWorkspace(settings) : new DisabledWorkspaceGit();
    }
}
