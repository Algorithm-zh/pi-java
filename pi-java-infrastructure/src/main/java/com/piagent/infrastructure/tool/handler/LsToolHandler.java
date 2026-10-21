package com.piagent.infrastructure.tool.handler;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.service.ToolArgumentGuard;
import com.piagent.infrastructure.tool.BuiltInToolHandler;
import com.piagent.infrastructure.tool.RealPathResolver;
import com.piagent.infrastructure.tool.ToolText;
import com.piagent.infrastructure.tool.WorkspaceWalker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code ls} 工具：列出工作区目录内容。
 *
 * <p>目录以 {@code /} 结尾；按路径排序，深度最多 3 层、条目最多 500 个。
 * 遍历不跟随符号链接，隐藏目录与构建产物目录直接跳过。</p>
 */
public final class LsToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("path", "depth");
    private static final int MAX_DEPTH = 3;
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_CONTENT_LENGTH = 60_000;

    private final RealPathResolver resolver;

    /**
     * 创建 ls 工具。
     *
     * @param resolver 真实路径解析器
     */
    public LsToolHandler(RealPathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        ToolArgumentGuard.rejectUnknown(arguments, ALLOWED_ARGUMENTS);
        String requested = ToolArgumentGuard.optionalString(arguments, "path", ".", 1_024);
        int depth = ToolArgumentGuard.optionalInt(arguments, "depth", 1, 1, MAX_DEPTH);
        Path directory = resolver.resolveReadable(invocation.sandbox(), requested);
        if (!Files.isDirectory(directory)) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "不是目录: " + requested);
        }
        WorkspaceWalker.WalkResult walk;
        try {
            walk = WorkspaceWalker.walk(directory, depth, MAX_ENTRIES, true);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "读取目录失败: " + requested, error);
        }
        Path displayRoot = resolver.workspaceRoot(invocation.sandbox());
        List<String> lines = walk.paths().stream()
                .map(path -> render(displayRoot, path))
                .sorted(Comparator.naturalOrder())
                .toList();
        String content = lines.isEmpty() ? "（空目录）" : ToolText.truncate(
                String.join("\n", lines), MAX_CONTENT_LENGTH);
        Map<String, Object> details = Map.of(
                "path", ToolText.relative(displayRoot, directory),
                "depth", depth,
                "entries", lines.size(),
                "truncated", walk.truncated());
        return ToolResult.success(invocation.callId(), invocation.tool(), content, details);
    }

    private String render(Path displayRoot, Path path) {
        String relative = ToolText.relative(displayRoot, path);
        return Files.isDirectory(path) ? relative + "/" : relative;
    }
}
