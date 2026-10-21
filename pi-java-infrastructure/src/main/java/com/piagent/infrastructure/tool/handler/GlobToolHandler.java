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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code glob} 工具：按 glob 模式查找文件。
 *
 * <p>模式相对搜索根解释，例如 {@code **}{@code /*.java}；拒绝绝对路径模式，
 * 防止绕过工作区语义。默认最多返回 200 条，与 grep 一样跳过隐藏目录与构建产物。</p>
 */
public final class GlobToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("pattern", "path", "maxResults");
    private static final int MAX_WALK_DEPTH = 32;
    private static final int MAX_WALKED_FILES = 20_000;
    private static final int MAX_CONTENT_LENGTH = 60_000;

    private final RealPathResolver resolver;

    /**
     * 创建 glob 工具。
     *
     * @param resolver 真实路径解析器
     */
    public GlobToolHandler(RealPathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        ToolArgumentGuard.rejectUnknown(arguments, ALLOWED_ARGUMENTS);
        String pattern = ToolArgumentGuard.requireString(arguments, "pattern", 500);
        String requested = ToolArgumentGuard.optionalString(arguments, "path", ".", 1_024);
        int maxResults = ToolArgumentGuard.optionalInt(arguments, "maxResults", 200, 1, 1_000);
        rejectAbsolutePattern(pattern);
        List<PathMatcher> matchers = compile(pattern);
        Path root = resolver.resolveReadable(invocation.sandbox(), requested);
        Path displayRoot = resolver.workspaceRoot(invocation.sandbox());
        MatchOutcome outcome = matches(root, displayRoot, matchers, maxResults);
        String content = outcome.paths().isEmpty()
                ? "（没有匹配）"
                : ToolText.truncate(String.join("\n", outcome.paths()), MAX_CONTENT_LENGTH);
        Map<String, Object> details = Map.of(
                "pattern", pattern,
                "matches", outcome.paths().size(),
                "truncated", outcome.truncated());
        return ToolResult.success(invocation.callId(), invocation.tool(), content, details);
    }

    private void rejectAbsolutePattern(String pattern) {
        String normalized = pattern.replace('\\', '/');
        boolean absolute = normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*");
        if (absolute) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "glob 模式必须相对工作区: " + pattern);
        }
    }

    private List<PathMatcher> compile(String pattern) {
        List<String> variants = new ArrayList<>();
        variants.add(pattern);
        if (pattern.startsWith("**/") && pattern.length() > 3) {
            // Java glob 的 **/ 需要至少一层目录；补一个去前缀变体以匹配根目录文件。
            variants.add(pattern.substring(3));
        }
        try {
            List<PathMatcher> matchers = new ArrayList<>();
            for (String variant : variants) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + variant));
            }
            return matchers;
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "glob 表达式不合法: " + pattern);
        }
    }

    private MatchOutcome matches(Path root, Path displayRoot, List<PathMatcher> matchers,
            int maxResults) {
        if (Files.isRegularFile(root)) {
            Path name = Path.of(root.getFileName().toString());
            boolean matched = matchers.stream().anyMatch(matcher -> matcher.matches(name));
            return new MatchOutcome(matched
                    ? List.of(ToolText.relative(displayRoot, root))
                    : List.of(), false);
        }
        if (!Files.isDirectory(root)) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "不是文件或目录: " + root);
        }
        WorkspaceWalker.WalkResult walk;
        try {
            walk = WorkspaceWalker.walk(root, MAX_WALK_DEPTH, MAX_WALKED_FILES, false);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "遍历目录失败: " + root, error);
        }
        List<String> matches = new ArrayList<>();
        boolean truncated = walk.truncated();
        for (Path file : walk.paths()) {
            Path relative = root.relativize(file);
            Path candidate = Path.of(relative.toString().replace('\\', '/'));
            if (matchers.stream().noneMatch(matcher -> matcher.matches(candidate))) {
                continue;
            }
            if (matches.size() >= maxResults) {
                truncated = true;
                break;
            }
            matches.add(ToolText.relative(displayRoot, file));
        }
        matches.sort(Comparator.naturalOrder());
        return new MatchOutcome(matches, truncated);
    }

    private record MatchOutcome(List<String> paths, boolean truncated) {
    }
}
