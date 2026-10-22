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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code edit} 工具：按字面文本替换文件内容。
 *
 * <p>默认要求 {@code oldText} 恰好匹配一处，匹配到多处时提示模型补充上下文，
 * 避免改错位置；显式传 {@code replaceAll=true} 才会替换全部。替换是纯字符串
 * 操作，不解释正则，因此模型给出的代码片段可以直接使用。</p>
 */
public final class EditToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("path", "oldText", "newText",
            "replaceAll");
    private static final int MAX_PATH_LENGTH = 1_024;
    private static final int MAX_OLD_TEXT_LENGTH = 100_000;
    private static final int MAX_NEW_TEXT_LENGTH = 1_000_000;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final long MAX_RESULT_CHARS = 2_000_000L;

    private final RealPathResolver resolver;

    /**
     * 创建 edit 工具。
     *
     * @param resolver 真实路径解析器
     */
    public EditToolHandler(RealPathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        ToolArgumentGuard.rejectUnknown(arguments, ALLOWED_ARGUMENTS);
        String requested = ToolArgumentGuard.requireString(arguments, "path", MAX_PATH_LENGTH);
        String oldText = ToolArgumentGuard.requireString(arguments, "oldText",
                MAX_OLD_TEXT_LENGTH);
        String newText = ToolArgumentGuard.requireText(arguments, "newText",
                MAX_NEW_TEXT_LENGTH);
        boolean replaceAll = ToolArgumentGuard.optionalBoolean(arguments, "replaceAll", false);
        Path file = resolver.resolveReadable(invocation.sandbox(), requested);
        requireEditableFile(file, requested);
        String original = read(file, requested);
        int occurrences = countOccurrences(original, oldText);
        if (occurrences == 0) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "未找到要替换的文本: " + requested);
        }
        if (occurrences > 1 && !replaceAll) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "文本匹配到 " + occurrences + " 处，请补充上下文或设置 replaceAll=true");
        }
        int replacements = replaceAll ? occurrences : 1;
        String updated = apply(original, oldText, newText, replacements);
        long estimated = original.length()
                + (long) (newText.length() - oldText.length()) * replacements;
        if (estimated > MAX_RESULT_CHARS) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "替换后的文件超过 " + MAX_RESULT_CHARS + " 个字符");
        }
        write(file, updated, requested);
        long bytes = sizeOf(file, requested);
        String relative = ToolText.relative(resolver.workspaceRoot(invocation.sandbox()), file);
        String message = "已更新文件 " + relative + "，替换 " + replacements
                + " 处（共匹配 " + occurrences + " 处）";
        Map<String, Object> details = Map.of(
                "path", relative,
                "occurrences", occurrences,
                "replacements", replacements,
                "bytes", bytes,
                "lines", lineCount(updated));
        return ToolResult.success(invocation.callId(), invocation.tool(), message, details);
    }

    private void requireEditableFile(Path file, String requested) {
        if (!Files.isRegularFile(file)) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "不是普通文件: " + requested);
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                        "文件超过 " + MAX_FILE_BYTES + " 字节，无法编辑: " + requested);
            }
            if (FileContentClassifier.isBinary(file)) {
                throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                        "不支持编辑二进制文件: " + requested);
            }
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "读取文件失败: " + requested, error);
        }
    }

    private String read(Path file, String requested) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "读取文件失败: " + requested, error);
        }
    }

    private void write(Path file, String content, String requested) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "写入文件失败: " + requested, error);
        }
    }

    private long sizeOf(Path file, String requested) {
        try {
            return Files.size(file);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "读取写入结果失败: " + requested, error);
        }
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private String apply(String original, String oldText, String newText, int replacements) {
        if (replacements > 1) {
            return original.replace(oldText, newText);
        }
        int index = original.indexOf(oldText);
        return original.substring(0, index) + newText
                + original.substring(index + oldText.length());
    }

    private int lineCount(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        return (int) content.lines().count();
    }
}
