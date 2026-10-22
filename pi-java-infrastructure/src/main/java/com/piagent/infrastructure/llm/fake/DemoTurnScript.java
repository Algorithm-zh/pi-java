package com.piagent.infrastructure.llm.fake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.Usage;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.TextDelta;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.shared.Ulid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo 模式的确定性回合脚本。
 *
 * <p>和 {@link FakeTurn} 的固定脚本不同，这里根据请求的最后一条消息决定产出：
 * 用户输入 {@code /read <path>}、{@code /ls}、{@code /write <path> <content>}、
 * {@code /tool <name> <json>} 或中文同义词时触发真实工具调用；工具结果回传后
 * 生成一段摘要文本；其余输入给出使用提示。它不访问网络，只用于零配置的
 * Web/CLI 演示和端到端测试。</p>
 */
final class DemoTurnScript {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PREVIEW_LENGTH = 1_200;
    private static final Pattern READ = Pattern.compile(
            "^(?:/read|read|读取文件|读取|读一下|看看|查看)\\s*[:：]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST = Pattern.compile(
            "^(?:/ls|ls|列出目录|列目录|目录)\\s*[:：]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE = Pattern.compile(
            "^(?:/write|write|创建文件|写入文件|写文件|写入)\\s+(\\S+)(?:\\s+([\\s\\S]*))?\\z",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL = Pattern.compile(
            "^(?:/tool|tool|调用工具)\\s+([a-z][a-z0-9._-]*)\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private DemoTurnScript() {
    }

    /**
     * 为请求选择下一个回合。
     *
     * @param request 模型请求
     * @return 确定性回合
     */
    static FakeTurn next(LlmRequest request) {
        AgentMessage last = request.messages().isEmpty() ? null
                : request.messages().get(request.messages().size() - 1);
        if (last instanceof ToolResultMessage result) {
            return text(toolSummary(result));
        }
        String input = FakeTurn.lastUserText(request);
        Matcher read = READ.matcher(input);
        if (read.matches()) {
            return toolCall("read", Map.of("path", pathOrDefault(read.group(1), "README.md")));
        }
        Matcher list = LIST.matcher(input);
        if (list.matches()) {
            return toolCall("ls", Map.of("path", pathOrDefault(list.group(1), ".")));
        }
        Matcher write = WRITE.matcher(input);
        if (write.matches()) {
            return toolCall("write", Map.of("path", stripQuotes(write.group(1)),
                    "content", contentOf(write.group(2))));
        }
        Matcher tool = TOOL.matcher(input);
        if (tool.matches()) {
            return toolCommand(tool.group(1), tool.group(2));
        }
        return text("（Fake 模型 · demo）我收到：" + (input.isBlank() ? "（空消息）" : input)
                + "\n\n试试输入 `/read README.md`、`/ls .`、`/write notes.txt 你好`"
                + "（写操作会先触发审批），或用 `/tool <工具名> <JSON参数>` "
                + "调用已启用的插件工具。");
    }

    private static FakeTurn toolCommand(String name, String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return toolCall(name, Map.of());
        }
        try {
            JsonNode arguments = JSON.readTree(rawArguments);
            if (arguments == null || !arguments.isObject()) {
                return invalidArguments();
            }
            return toolCall(name, JSON.convertValue(arguments,
                    new TypeReference<Map<String, Object>>() { }));
        } catch (JsonProcessingException error) {
            return invalidArguments();
        }
    }

    private static FakeTurn invalidArguments() {
        return text("（Fake 模型 · demo）工具参数必须是 JSON 对象。"
                + "示例：`/tool app.test.sample.echo {\"text\":\"你好\"}`");
    }

    private static FakeTurn toolCall(String toolName, Map<String, Object> arguments) {
        String callId = "demo-" + Ulid.generate(Instant.now());
        return FakeTurn.toolCall(callId, toolName, arguments);
    }

    private static FakeTurn text(String value) {
        long tokens = Math.max(1, value.length() / 4);
        LlmResponse response = new LlmResponse(List.of(new TextBlock(value)),
                new Usage(tokens, tokens, 0, 0, tokens * 2), AssistantStopReason.STOP, null);
        return new FakeTurn(List.of(new TextDelta(value)), response, null, Duration.ZERO);
    }

    private static String toolSummary(ToolResultMessage result) {
        String preview = result.content().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        if (preview.length() > PREVIEW_LENGTH) {
            preview = preview.substring(0, PREVIEW_LENGTH) + "\n…（结果已截断）";
        }
        String status = result.isError() ? "返回错误" : "返回结果";
        return "（Fake 模型 · demo）工具 `" + result.toolName() + "` 已" + status + "：\n\n"
                + preview;
    }

    private static String pathOrDefault(String raw, String fallback) {
        String value = stripQuotes(raw);
        if (value.isEmpty()) {
            return fallback;
        }
        return value.lines().findFirst().orElse(fallback).trim();
    }

    private static String stripQuotes(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2
                && ((value.startsWith("`") && value.endsWith("`"))
                || (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String contentOf(String raw) {
        String content = stripQuotes(raw);
        return content.replace("\\n", "\n").replace("\\t", "\t");
    }
}
