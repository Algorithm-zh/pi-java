package com.piagent.infrastructure.tool.handler;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.service.ToolArgumentGuard;
import com.piagent.infrastructure.tool.BuiltInToolHandler;
import com.piagent.infrastructure.tool.FileContentClassifier;
import com.piagent.infrastructure.tool.RealPathResolver;
import com.piagent.infrastructure.tool.ToolText;
import com.piagent.infrastructure.tool.WorkspaceWalker;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code grep} 工具：按正则搜索文件内容。
 *
 * <p>先按文件名或相对路径的 glob 过滤，再逐行匹配；跳过二进制、超大文件与
 * 构建产物。结果数、行片段长度与总输出都有上限，并在 details 中如实报告
 * 截断情况，避免模型把不完整结果当成全量。</p>
 */
public final class GrepToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS =
            Set.of("pattern", "path", "glob", "ignoreCase", "maxResults");
    private static final int MAX_FILES = 5_000;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SNIPPET_LENGTH = 300;
    private static final int MAX_CONTENT_LENGTH = 80_000;
    private static final int MAX_WALK_DEPTH = 32;

    private final RealPathResolver resolver;

    /**
     * 创建 grep 工具。
     *
     * @param resolver 真实路径解析器
     */
    public GrepToolHandler(RealPathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        ToolArgumentGuard.rejectUnknown(arguments, ALLOWED_ARGUMENTS);
        String regex = ToolArgumentGuard.requireString(arguments, "pattern", 1_000);
        String requested = ToolArgumentGuard.optionalString(arguments, "path", ".", 1_024);
        String globFilter = ToolArgumentGuard.optionalString(arguments, "glob", null, 500);
        boolean ignoreCase = ToolArgumentGuard.optionalBoolean(arguments, "ignoreCase", false);
        int maxResults = ToolArgumentGuard.optionalInt(arguments, "maxResults", 100, 1, 500);
        Pattern pattern = compilePattern(regex, ignoreCase);
        Path root = resolver.resolveReadable(invocation.sandbox(), requested);
        Path displayRoot = resolver.workspaceRoot(invocation.sandbox());
        SearchTarget target = collectFiles(root, requested);
        SearchOutcome outcome = search(target, displayRoot, globFilter, pattern, maxResults);
        String content = outcome.lines().isEmpty()
                ? "（没有匹配）"
                : ToolText.truncate(String.join("\n", outcome.lines()), MAX_CONTENT_LENGTH);
        Map<String, Object> details = new HashMap<>();
        details.put("matches", outcome.lines().size());
        details.put("filesScanned", outcome.filesScanned());
        details.put("skippedBinary", outcome.skippedBinary());
        details.put("skippedLarge", outcome.skippedLarge());
        details.put("truncated", outcome.truncated() || target.truncated());
        return ToolResult.success(invocation.callId(), invocation.tool(), content, details);
    }

    private Pattern compilePattern(String regex, boolean ignoreCase) {
        try {
            return Pattern.compile(regex, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException error) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "正则表达式不合法: " + error.getDescription());
        }
    }

    private PathMatcher compileGlob(String glob) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "glob 表达式不合法: " + glob);
        }
    }

    private SearchTarget collectFiles(Path root, String requested) {
        if (Files.isRegularFile(root)) {
            return new SearchTarget(List.of(root), false);
        }
        if (!Files.isDirectory(root)) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "不是文件或目录: " + requested);
        }
        try {
            WorkspaceWalker.WalkResult walk =
                    WorkspaceWalker.walk(root, MAX_WALK_DEPTH, MAX_FILES, false);
            return new SearchTarget(walk.paths(), walk.truncated());
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "遍历目录失败: " + requested, error);
        }
    }

    private SearchOutcome search(SearchTarget target, Path displayRoot, String globFilter,
            Pattern pattern, int maxResults) {
        PathMatcher globMatcher = globFilter == null ? null : compileGlob(globFilter);
        boolean matchFullPath = globFilter != null
                && (globFilter.contains("/") || globFilter.contains("\\"));
        List<String> lines = new ArrayList<>();
        int filesScanned = 0;
        int skippedBinary = 0;
        int skippedLarge = 0;
        boolean truncated = false;
        for (Path file : target.files()) {
            if (globMatcher != null
                    && !matchesGlob(globMatcher, displayRoot, file, matchFullPath)) {
                continue;
            }
            filesScanned++;
            try {
                if (Files.size(file) > MAX_FILE_BYTES) {
                    skippedLarge++;
                    continue;
                }
                if (FileContentClassifier.isBinary(file)) {
                    skippedBinary++;
                    continue;
                }
                if (appendMatches(file, displayRoot, pattern, maxResults, lines)) {
                    truncated = true;
                    break;
                }
            } catch (IOException ignored) {
                // 单个文件读失败不影响其余搜索结果。
            }
        }
        return new SearchOutcome(lines, filesScanned, skippedBinary, skippedLarge, truncated);
    }

    private boolean appendMatches(Path file, Path displayRoot, Pattern pattern, int maxResults,
            List<String> lines) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!pattern.matcher(line).find()) {
                    continue;
                }
                if (lines.size() >= maxResults) {
                    return true;
                }
                lines.add(ToolText.relative(displayRoot, file) + ":" + lineNumber + ": "
                        + ToolText.truncate(line.strip(), MAX_SNIPPET_LENGTH));
            }
        }
        return false;
    }

    private boolean matchesGlob(PathMatcher matcher, Path displayRoot, Path file,
            boolean matchFullPath) {
        Path candidate = matchFullPath ? displayRoot.relativize(file) : file.getFileName();
        return matcher.matches(Path.of(candidate.toString().replace('\\', '/')));
    }

    private record SearchTarget(List<Path> files, boolean truncated) {
    }

    private record SearchOutcome(List<String> lines, int filesScanned, int skippedBinary,
            int skippedLarge, boolean truncated) {
    }
}
