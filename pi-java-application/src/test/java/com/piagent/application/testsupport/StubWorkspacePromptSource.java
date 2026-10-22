package com.piagent.application.testsupport;

import com.piagent.domain.context.port.WorkspacePromptSource;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 应用层测试用的工作区文档源。
 *
 * <p>默认返回空（模拟没有 AGENTS.md）；可设置内容或异常，验证组装器的容错分支。</p>
 */
public final class StubWorkspacePromptSource implements WorkspacePromptSource {

    private String content;
    private RuntimeException failure;

    /**
     * 设置文档内容。
     *
     * @param value 内容
     * @return 自身
     */
    public StubWorkspacePromptSource withContent(String value) {
        this.content = value;
        return this;
    }

    /**
     * 让读取抛异常。
     *
     * @param error 异常
     * @return 自身
     */
    public StubWorkspacePromptSource failing(RuntimeException error) {
        this.failure = error;
        return this;
    }

    @Override
    public Optional<String> readAgentsDocument(Path cwd) {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(content);
    }
}
