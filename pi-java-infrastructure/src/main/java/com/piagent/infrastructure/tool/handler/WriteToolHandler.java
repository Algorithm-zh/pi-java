package com.piagent.infrastructure.tool.handler;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.service.ToolArgumentGuard;
import com.piagent.infrastructure.tool.BuiltInToolHandler;
import com.piagent.infrastructure.tool.RealPathResolver;
import com.piagent.infrastructure.tool.ToolText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code write} 工具：创建或整体覆盖一个 UTF-8 文本文件。
 *
 * <p>写入前先解析真实路径，目标可以尚不存在，但最近的已存在祖先必须落在
 * 可写根内；父目录会自动创建。工具不做内容校验或格式化，覆盖语义对模型
 * 是明确的：正文就是文件的全部内容。</p>
 */
public final class WriteToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("path", "content");
    private static final int MAX_PATH_LENGTH = 1_024;
    private static final int MAX_CONTENT_LENGTH = 1_000_000;

    private final RealPathResolver resolver;

    /**
     * 创建 write 工具。
     *
     * @param resolver 真实路径解析器
     */
    public WriteToolHandler(RealPathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        ToolArgumentGuard.rejectUnknown(arguments, ALLOWED_ARGUMENTS);
        String requested = ToolArgumentGuard.requireString(arguments, "path", MAX_PATH_LENGTH);
        String content = ToolArgumentGuard.requireText(arguments, "content", MAX_CONTENT_LENGTH);
        Path target = resolver.resolveWritable(invocation.sandbox(), requested);
        if (Files.isDirectory(target)) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "目标是目录，无法写入文件: " + requested);
        }
        boolean created = !Files.exists(target);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "写入文件失败: " + requested, error);
        }
        long bytes = sizeOf(target, requested);
        String relative = ToolText.relative(resolver.workspaceRoot(invocation.sandbox()), target);
        String message = "已" + (created ? "创建" : "覆盖") + "文件 " + relative
                + "（" + bytes + " 字节，" + lineCount(content) + " 行）";
        Map<String, Object> details = Map.of(
                "path", relative,
                "bytes", bytes,
                "lines", lineCount(content),
                "created", created);
        return ToolResult.success(invocation.callId(), invocation.tool(), message, details);
    }

    private long sizeOf(Path target, String requested) {
        try {
            return Files.size(target);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "读取写入结果失败: " + requested, error);
        }
    }

    private int lineCount(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        return (int) content.lines().count();
    }
}
