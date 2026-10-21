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
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code read} 工具：按行读取 UTF-8 文本并输出行号。
 *
 * <p>只读取需要的行区间，不把整文件载入内存；单行与总输出都有上限。
 * 二进制文件（前 8KB 含 NUL）直接拒绝，避免把乱码塞进上下文。</p>
 */
public final class ReadToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("path", "offset", "limit");
    private static final int DEFAULT_LIMIT = 400;
    private static final int MAX_LIMIT = 2_000;
    private static final int MAX_LINE_LENGTH = 2_000;
    private static final int MAX_CONTENT_LENGTH = 80_000;

    private final RealPathResolver resolver;

    /**
     * 创建 read 工具。
     *
     * @param resolver 真实路径解析器
     */
    public ReadToolHandler(RealPathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        ToolArgumentGuard.rejectUnknown(arguments, ALLOWED_ARGUMENTS);
        String requested = ToolArgumentGuard.requireString(arguments, "path", 1_024);
        int offset = ToolArgumentGuard.optionalInt(arguments, "offset", 1, 1, Integer.MAX_VALUE);
        int limit = ToolArgumentGuard.optionalInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        Path file = resolver.resolveReadable(invocation.sandbox(), requested);
        if (!Files.isRegularFile(file)) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "不是普通文件: " + requested);
        }
        boolean binary;
        long size;
        try {
            binary = FileContentClassifier.isBinary(file);
            size = Files.size(file);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "读取文件失败: " + requested, error);
        }
        if (binary) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "不支持读取二进制文件: " + requested);
        }
        Reading reading = readLines(file, requested, offset, limit);
        String content = reading.content().isEmpty()
                ? "（该区间没有内容，文件共 " + reading.lastLine() + " 行）"
                : reading.content();
        Map<String, Object> details = Map.of(
                "path", ToolText.relative(resolver.workspaceRoot(invocation.sandbox()), file),
                "offset", offset,
                "limit", limit,
                "lines", reading.emittedLines(),
                "lastLine", reading.lastLine(),
                "sizeBytes", size,
                "truncated", reading.truncated());
        return ToolResult.success(invocation.callId(), invocation.tool(), content, details);
    }

    private Reading readLines(Path file, String requested, int offset, int limit) {
        StringBuilder content = new StringBuilder();
        int lineNumber = 0;
        int emitted = 0;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < offset) {
                    continue;
                }
                if (emitted >= limit) {
                    truncated = true;
                    break;
                }
                String rendered = render(lineNumber, line);
                if (content.length() + rendered.length() > MAX_CONTENT_LENGTH) {
                    truncated = true;
                    break;
                }
                content.append(rendered);
                emitted++;
            }
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "读取文件失败: " + requested, error);
        }
        return new Reading(content.toString(), emitted, lineNumber, truncated);
    }

    private String render(int lineNumber, String line) {
        String display = line.length() > MAX_LINE_LENGTH
                ? line.substring(0, MAX_LINE_LENGTH) + "…"
                : line;
        return String.format("%5d| %s\n", lineNumber, display);
    }

    private record Reading(String content, int emittedLines, int lastLine, boolean truncated) {
    }
}
