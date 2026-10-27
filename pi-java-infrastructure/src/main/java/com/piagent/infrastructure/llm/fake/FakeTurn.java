package com.piagent.infrastructure.llm.fake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.Usage;
import com.piagent.domain.model.llm.LlmDelta;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.TextDelta;
import com.piagent.domain.model.llm.ToolCallDelta;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ImageBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fake provider 的单个脚本回合。
 *
 * <p>回合只描述“产出哪些 delta、最终响应是什么、是否在某个位置失败”。
 * {@link DoneDelta} 由流实现自动补发，脚本不要自己加入。</p>
 *
 * @param deltas 要产出的增量
 * @param response 成功时的最终响应；失败回合为 null
 * @param failure 失败描述；成功回合为 null
 * @param deltaDelay 每个 delta 之间的延迟，null 视为 0
 */
public record FakeTurn(List<LlmDelta> deltas, LlmResponse response, Failure failure,
        Duration deltaDelay) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 校验脚本回合。
     *
     * @param deltas 增量
     * @param response 最终响应
     * @param failure 失败描述
     * @param deltaDelay 延迟
     */
    public FakeTurn {
        Objects.requireNonNull(deltas, "deltas 不能为空");
        deltas = List.copyOf(deltas);
        deltaDelay = deltaDelay == null ? Duration.ZERO : deltaDelay;
        if (failure == null && response == null) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "FakeTurn 必须提供响应或失败");
        }
        if (failure != null) {
            if (response != null) {
                throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "失败回合不能同时提供响应");
            }
            if (failure.afterDeltas() != deltas.size()) {
                throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                        "失败必须发生在全部 delta 之后，避免脚本出现不可达增量");
            }
        }
    }

    /**
     * 失败描述。
     *
     * @param code 错误码
     * @param message 可读消息
     * @param afterDeltas 在产出多少个 delta 后失败
     */
    public record Failure(LlmErrorCode code, String message, int afterDeltas) {

        /**
         * 校验失败描述。
         *
         * @param code 错误码
         * @param message 消息
         * @param afterDeltas 失败位置
         */
        public Failure {
            Objects.requireNonNull(code, "code 不能为空");
            message = message == null || message.isBlank() ? "fake provider failure" : message;
            if (afterDeltas < 0) {
                throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "afterDeltas 不能为负数");
            }
        }
    }

    /**
     * 构造纯文本回合。
     *
     * @param text 文本
     * @return 脚本回合
     */
    public static FakeTurn text(String text) {
        TextBlock block = new TextBlock(text);
        LlmResponse response = new LlmResponse(List.of(block), Usage.zero(),
                AssistantStopReason.STOP, null);
        return new FakeTurn(List.of(new TextDelta(text)), response, null, Duration.ZERO);
    }

    /**
     * 构造工具调用回合。
     *
     * @param toolCallId 调用 ID
     * @param toolName 工具名
     * @param arguments 参数
     * @return 脚本回合
     */
    public static FakeTurn toolCall(String toolCallId, String toolName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        String fragment = argumentsJson(safeArguments);
        int split = fragment.length() / 2;
        List<LlmDelta> deltas = List.of(
                new ToolCallDelta(0, toolCallId, toolName, fragment.substring(0, split)),
                new ToolCallDelta(0, "", "", fragment.substring(split)));
        LlmResponse response = new LlmResponse(List.of(new ToolCallBlock(toolCallId, toolName, safeArguments)),
                Usage.zero(), AssistantStopReason.TOOL_USE, null);
        return new FakeTurn(deltas, response, null, Duration.ZERO);
    }

    /**
     * 构造“产出部分 delta 后失败”的回合。
     *
     * @param text 失败前产出的文本
     * @param code 失败错误码
     * @param message 失败消息
     * @return 脚本回合
     */
    public static FakeTurn partialThenFailure(String text, LlmErrorCode code, String message) {
        List<LlmDelta> deltas = List.of(new TextDelta(text));
        return new FakeTurn(deltas, null, new Failure(code, message, deltas.size()), Duration.ZERO);
    }

    /**
     * 构造在首个 delta 前失败的回合。
     *
     * @param code 失败错误码
     * @param message 失败消息
     * @return 脚本回合
     */
    public static FakeTurn failure(LlmErrorCode code, String message) {
        return new FakeTurn(List.of(), null, new Failure(code, message, 0), Duration.ZERO);
    }

    /**
     * 根据最后一次用户文本生成回声回合。
     *
     * @param request 请求
     * @return 脚本回合
     */
    public static FakeTurn echo(LlmRequest request) {
        String input = lastUserText(request);
        int images = lastUserImageCount(request);
        String text;
        if (input.isBlank()) {
            text = images == 0
                    ? "（Fake 模型）我已经收到请求。"
                    : "（Fake 模型）我已经收到 " + images + " 张图片。";
        } else {
            text = "（Fake 模型）收到：" + input
                    + (images == 0 ? "" : "（另附 " + images + " 张图片）");
        }
        long tokens = Math.max(1, text.length() / 4);
        LlmResponse response = new LlmResponse(List.of(new TextBlock(text)),
                new Usage(tokens, tokens, 0, 0, tokens * 2), AssistantStopReason.STOP, null);
        return new FakeTurn(List.of(new TextDelta(text)), response, null, Duration.ZERO);
    }

    /**
     * 设置 delta 间延迟。
     *
     * @param delay 延迟
     * @return 新脚本回合
     */
    public FakeTurn withDelay(Duration delay) {
        return new FakeTurn(deltas, response, failure, delay);
    }

    /**
     * 设置最终用量。
     *
     * @param usage 用量
     * @return 新脚本回合
     */
    public FakeTurn withUsage(Usage usage) {
        if (response == null) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "失败回合不能设置用量");
        }
        LlmResponse updated = new LlmResponse(response.content(), usage, response.stopReason(),
                response.errorMessage());
        return new FakeTurn(deltas, updated, failure, deltaDelay);
    }

    /**
     * 追加自定义 delta。
     *
     * @param extra 增量
     * @return 新脚本回合
     */
    public FakeTurn withDeltas(List<LlmDelta> extra) {
        List<LlmDelta> merged = new ArrayList<>(deltas);
        merged.addAll(extra);
        return new FakeTurn(merged, response, failure, deltaDelay);
    }

    private static String argumentsJson(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments);
        } catch (JsonProcessingException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "Fake 工具参数无法序列化", error);
        }
    }

    static String lastUserText(LlmRequest request) {
        for (int index = request.messages().size() - 1; index >= 0; index--) {
            if (request.messages().get(index) instanceof UserMessage user) {
                return user.content().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::text)
                        .reduce("", String::concat)
                        .trim();
            }
        }
        return "";
    }

    static int lastUserImageCount(LlmRequest request) {
        for (int index = request.messages().size() - 1; index >= 0; index--) {
            if (request.messages().get(index) instanceof UserMessage user) {
                return (int) user.content().stream()
                        .filter(ImageBlock.class::isInstance)
                        .count();
            }
        }
        return 0;
    }
}
