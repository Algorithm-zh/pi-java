package com.piagent.domain.context.port;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 工作区补充提示文档端口。
 *
 * <p>v0.1 只读取 cwd 根目录的 AGENTS.md；大小上限与文件访问由基础设施负责。</p>
 */
public interface WorkspacePromptSource {

    /**
     * 读取工作区约定文档。
     *
     * @param cwd 工作目录
     * @return 文档内容；不存在或不可读时为空
     */
    Optional<String> readAgentsDocument(Path cwd);
}
