package com.piagent.infrastructure.tool.handler;

import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.service.ToolArgumentGuard;
import com.piagent.infrastructure.tool.BuiltInToolHandler;
import com.piagent.infrastructure.tool.RealPathResolver;
import com.piagent.infrastructure.tool.ToolText;
import com.piagent.infrastructure.tool.index.RepoMap;
import com.piagent.infrastructure.tool.index.WorkspaceCodeIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code repo_map} 工具：仓库概览与按任务定位文件。
 *
 * <p>只读、不执行工作区代码。无 query 时返回语言分布、目录结构与关键文件；
 * 有 query 时返回相关文件排名、符号与匹配行。路径先经过
 * {@link RealPathResolver}，越界会直接失败。</p>
 */
public final class RepoMapToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("query", "path", "maxFiles");
    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_PATH_LENGTH = 1_024;
    private static final int MAX_CONTENT_LENGTH = 16_000;

    private final RealPathResolver resolver;
    private final WorkspaceCodeIndex index;

    /**
     * 创建仓库地图工具。
     *
     * @param resolver 真实路径解析器
     */
    public RepoMapToolHandler(RealPathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
        this.index = new WorkspaceCodeIndex();
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        ToolArgumentGuard.rejectUnknown(arguments, ALLOWED_ARGUMENTS);
        String query = ToolArgumentGuard.optionalString(arguments, "query", "",
                MAX_QUERY_LENGTH);
        String requested = ToolArgumentGuard.optionalString(arguments, "path", ".",
                MAX_PATH_LENGTH);
        int maxFiles = ToolArgumentGuard.optionalInt(arguments, "maxFiles", 20, 1, 50);
        Path workspace = resolver.workspaceRoot(invocation.sandbox());
        Path target = resolver.resolveReadable(invocation.sandbox(),
                requested.isBlank() ? "." : requested);
        RepoMap map = map(workspace, target, query, maxFiles);
        String content = ToolText.truncate(map.text(), MAX_CONTENT_LENGTH);
        Map<String, Object> details = new HashMap<>();
        details.put("matchedFiles", map.matchedFiles());
        details.put("indexedFiles", map.indexedFiles());
        details.put("truncated", map.truncated());
        return ToolResult.success(invocation.callId(), invocation.tool(), content, details);
    }

    private RepoMap map(Path workspace, Path target, String query, int maxFiles) {
        boolean file = Files.isRegularFile(target);
        if (target.startsWith(workspace)) {
            String prefix = target.equals(workspace) ? "" : ToolText.relative(workspace, target);
            return index.search(workspace, prefix, file, query, maxFiles);
        }
        Path root = file ? target.getParent() : target;
        String prefix = file ? target.getFileName().toString() : "";
        return index.search(root, prefix, file, query, maxFiles);
    }
}
