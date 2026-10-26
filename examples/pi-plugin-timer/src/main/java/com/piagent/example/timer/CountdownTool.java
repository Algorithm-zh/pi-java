package com.piagent.example.timer;

import com.piagent.plugin.api.PluginRisk;
import com.piagent.plugin.api.PluginTool;
import com.piagent.plugin.api.PluginToolCall;
import com.piagent.plugin.api.PluginToolContext;
import com.piagent.plugin.api.PluginToolResult;
import com.piagent.plugin.api.ToolSpec;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 倒计时工具：等待指定秒数后返回，用于演示插件工具调用与取消。
 *
 * <p>等待期间每 100 毫秒检查一次取消令牌与线程中断，避免长时间占用执行线程。
 * 参数非法或执行被取消时返回 {@code error=true}，不抛出异常。</p>
 */
public final class CountdownTool implements PluginTool {

    private static final String SCHEMA = """
            {"type":"object",
             "properties":{
               "seconds":{"type":"number","title":"等待秒数","minimum":0.1,"maximum":300,
                          "description":"要等待的秒数，支持小数"},
               "label":{"type":"string","title":"计时器名称","maxLength":64,
                        "description":"可选，用来在结果里区分不同的计时器"}},
             "required":["seconds"],"additionalProperties":false}
            """;

    private static final double MIN_SECONDS = 0.1;
    private static final double MAX_SECONDS = 300.0;
    private static final long TICK_MILLIS = 100L;

    @Override
    public ToolSpec spec() {
        return new ToolSpec("countdown", "等待指定秒数后返回，适合演示插件工具、可控延迟与取消",
                SCHEMA, PluginRisk.LOW, Duration.ofSeconds(330), true);
    }

    @Override
    public PluginToolResult execute(PluginToolCall call, PluginToolContext context) {
        Double seconds = parseSeconds(call.arguments().get("seconds"));
        if (seconds == null) {
            return PluginToolResult.failure("seconds 必须是 " + MIN_SECONDS + " 到 "
                    + MAX_SECONDS + " 之间的数字");
        }
        String label = parseLabel(call.arguments().get("label"));
        if (label == null) {
            return PluginToolResult.failure("label 必须是不超过 64 个字符的字符串");
        }

        long totalMillis = Math.round(seconds * 1000);
        long startedAt = System.nanoTime();
        while (true) {
            if (context.cancellation().isCancelled() || Thread.currentThread().isInterrupted()) {
                return cancelled(startedAt, label);
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            if (elapsedMillis >= totalMillis) {
                break;
            }
            try {
                Thread.sleep(Math.min(TICK_MILLIS, totalMillis - elapsedMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return cancelled(startedAt, label);
            }
        }

        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("requestedSeconds", String.format(Locale.ROOT, "%.2f", seconds));
        metadata.put("elapsedSeconds", String.format(Locale.ROOT, "%.2f", elapsedSeconds));
        metadata.put("label", label);
        return new PluginToolResult(String.format(Locale.ROOT,
                "定时器%s完成：已等待 %.1f 秒。", displayName(label), elapsedSeconds),
                false, metadata);
    }

    private PluginToolResult cancelled(long startedAt, String label) {
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        return PluginToolResult.failure(String.format(Locale.ROOT,
                "定时器%s已取消：已等待 %.1f 秒。", displayName(label), elapsedSeconds));
    }

    private String displayName(String label) {
        return label.isEmpty() ? "" : "「" + label + "」";
    }

    private Double parseSeconds(Object raw) {
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else if (raw instanceof String text) {
            try {
                value = Double.parseDouble(text.trim());
            } catch (NumberFormatException invalid) {
                return null;
            }
        } else {
            return null;
        }
        if (!Double.isFinite(value) || value < MIN_SECONDS || value > MAX_SECONDS) {
            return null;
        }
        return value;
    }

    private String parseLabel(Object raw) {
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String label)) {
            return null;
        }
        String trimmed = label.trim();
        return trimmed.length() > 64 ? null : trimmed;
    }
}
