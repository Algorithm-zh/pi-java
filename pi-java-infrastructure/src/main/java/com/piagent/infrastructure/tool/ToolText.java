package com.piagent.infrastructure.tool;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 工具输出文本的小工具：路径展示与截断。
 *
 * <p>给模型看的路径统一使用 {@code /}，避免 Windows 反斜杠在 JSON 与提示词中
 * 需要二次转义；截断时保留明确标记，防止模型误以为输出完整。</p>
 */
public final class ToolText {

    private ToolText() {
    }

    /**
     * 计算相对路径；跨盘符等无法相对化时退回绝对路径。
     *
     * @param root 根目录
     * @param path 目标路径
     * @return 使用 {@code /} 的展示路径
     */
    public static String relative(Path root, Path path) {
        Objects.requireNonNull(root, "root 不能为空");
        Objects.requireNonNull(path, "path 不能为空");
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absolutePath = path.toAbsolutePath().normalize();
        try {
            String relative = absoluteRoot.relativize(absolutePath).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        } catch (IllegalArgumentException error) {
            return absolutePath.toString().replace('\\', '/');
        }
    }

    /**
     * 截断文本并追加标记。
     *
     * @param text 原文
     * @param maxLength 最大字符数
     * @return 截断后的文本
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "…（已截断）";
    }
}
