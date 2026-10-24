package com.piagent.trigger.cli;

import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CLI 输出用的文本压缩工具。
 *
 * <p>终端宽度不可假设，所有来自模型或工具的内容在单行摘要里都要折叠换行并截断，
 * 避免一屏被一条工具输出刷满。</p>
 */
public final class CliText {

    private CliText() {
    }

    /**
     * 把任意文本折叠为单行并截断。
     *
     * @param value 原文本，null 视为空串
     * @param max 最大字符数
     * @return 单行摘要
     */
    public static String oneLine(String value, int max) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").strip();
        if (flattened.length() <= max) {
            return flattened;
        }
        return flattened.substring(0, Math.max(0, max)) + "…";
    }

    /**
     * 截断文本但保留换行。
     *
     * @param value 原文本
     * @param max 最大字符数
     * @return 截断后的文本
     */
    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max)) + "…";
    }

    /**
     * 提取内容块里的纯文本。
     *
     * @param blocks 内容块
     * @return 用换行连接的文本
     */
    public static String textOf(List<ContentBlock> blocks) {
        if (blocks == null) {
            return "";
        }
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .collect(Collectors.joining("\n"));
    }
}
