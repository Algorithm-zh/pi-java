package com.piagent.infrastructure.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.Usage;
import com.piagent.domain.model.llm.LlmDelta;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.TextDelta;
import com.piagent.domain.model.llm.ThinkingDelta;
import com.piagent.domain.model.llm.ToolCallDelta;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ThinkingBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAI-compatible SSE chunk 解析器。
 *
 * <p>解析器只负责把单个 data JSON 转成增量并累积最终响应；HTTP、重试、
 * 取消与超时由 {@link OpenAiEventStream} 负责。工具调用按 index 归并，
 * 参数片段在 finish 时统一解析。</p>
 */
public final class OpenAiSseParser {

    private final ObjectMapper mapper;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
    private Usage usage = Usage.zero();
    private AssistantStopReason finishReason;
    private boolean received;

    /**
     * 创建解析器。
     *
     * @param mapper Jackson 映射器
     */
    public OpenAiSseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 解析一个 SSE data 载荷。
     *
     * @param data JSON
     * @return 本 chunk 产生的增量
     */
    public List<LlmDelta> accept(String data) {
        JsonNode chunk = readObject(data);
        received = true;
        updateUsage(chunk.path("usage"));
        List<LlmDelta> deltas = new ArrayList<>();
        for (JsonNode choice : chunk.path("choices")) {
            JsonNode delta = choice.path("delta");
            appendText(delta.path("content"), deltas);
            appendThinking(delta.path("reasoning_content"), deltas);
            appendThinking(delta.path("reasoning"), deltas);
            appendToolCalls(delta.path("tool_calls"), deltas);
            String reason = choice.path("finish_reason").asText("");
            if (!reason.isBlank()) {
                finishReason = mapFinishReason(reason);
            }
        }
        return deltas;
    }

    /**
     * 构造最终响应。
     *
     * @return 完整响应
     */
    public LlmResponse finish() {
        if (!received) {
            throw new LlmGatewayException(LlmErrorCode.MALFORMED_RESPONSE, "LLM 流没有返回任何数据");
        }
        List<ContentBlock> content = new ArrayList<>();
        if (!thinking.isEmpty()) {
            content.add(new ThinkingBlock(thinking.toString(), null));
        }
        if (!text.isEmpty()) {
            content.add(new TextBlock(text.toString()));
        }
        boolean hasToolCalls = false;
        for (ToolCallBuilder builder : toolCalls.values()) {
            content.add(builder.toBlock(mapper));
            hasToolCalls = true;
        }
        AssistantStopReason stopReason = resolveStopReason(hasToolCalls);
        return new LlmResponse(content, normalizeUsage(), stopReason, null);
    }

    private JsonNode readObject(String data) {
        try {
            JsonNode node = mapper.readTree(data);
            if (node == null || !node.isObject()) {
                throw new LlmGatewayException(LlmErrorCode.MALFORMED_RESPONSE, "SSE data 不是 JSON 对象");
            }
            return node;
        } catch (LlmGatewayException error) {
            throw error;
        } catch (Exception error) {
            throw new LlmGatewayException(LlmErrorCode.MALFORMED_RESPONSE,
                    "SSE data 不是合法 JSON", error);
        }
    }

    private void appendText(JsonNode node, List<LlmDelta> deltas) {
        if (node.isTextual() && !node.asText().isEmpty()) {
            text.append(node.asText());
            deltas.add(new TextDelta(node.asText()));
        }
    }

    private void appendThinking(JsonNode node, List<LlmDelta> deltas) {
        if (node.isTextual() && !node.asText().isEmpty()) {
            thinking.append(node.asText());
            deltas.add(new ThinkingDelta(node.asText()));
        }
    }

    private void appendToolCalls(JsonNode calls, List<LlmDelta> deltas) {
        int position = 0;
        for (JsonNode call : calls) {
            int index = call.path("index").asInt(position++);
            String id = call.path("id").asText("");
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            String arguments = function.path("arguments").asText("");
            ToolCallBuilder builder = toolCalls.computeIfAbsent(index, ignored -> new ToolCallBuilder());
            builder.append(id, name, arguments);
            if (!id.isEmpty() || !name.isEmpty() || !arguments.isEmpty()) {
                deltas.add(new ToolCallDelta(index, id, name, arguments));
            }
        }
    }

    private void updateUsage(JsonNode node) {
        if (!node.isObject()) {
            return;
        }
        long input = node.path("prompt_tokens").asLong(0);
        long output = node.path("completion_tokens").asLong(0);
        long total = node.path("total_tokens").asLong(0);
        long cached = node.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        long cacheHit = node.path("prompt_cache_hit_tokens").asLong(0);
        usage = new Usage(input, output, Math.max(cached, cacheHit), 0, total);
    }

    private Usage normalizeUsage() {
        if (usage.totalTokens() > 0) {
            return usage;
        }
        long total = usage.input() + usage.output();
        return new Usage(usage.input(), usage.output(), usage.cacheRead(), usage.cacheWrite(), total);
    }

    private AssistantStopReason resolveStopReason(boolean hasToolCalls) {
        if (finishReason == null) {
            return hasToolCalls ? AssistantStopReason.TOOL_USE : AssistantStopReason.STOP;
        }
        if (finishReason == AssistantStopReason.TOOL_USE && !hasToolCalls) {
            throw new LlmGatewayException(LlmErrorCode.MALFORMED_RESPONSE,
                    "provider 声明 tool_calls 结束原因，但没有返回工具调用");
        }
        return finishReason;
    }

    private AssistantStopReason mapFinishReason(String reason) {
        return switch (reason) {
            case "length" -> AssistantStopReason.LENGTH;
            case "tool_calls", "function_call" -> AssistantStopReason.TOOL_USE;
            default -> AssistantStopReason.STOP;
        };
    }

    private static final class ToolCallBuilder {

        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        void append(String newId, String newName, String fragment) {
            if (!newId.isEmpty()) {
                id = newId;
            }
            if (!newName.isEmpty()) {
                name = name.isEmpty() ? newName : name + newName;
            }
            arguments.append(fragment);
        }

        ToolCallBlock toBlock(ObjectMapper mapper) {
            if (id.isBlank() || name.isBlank()) {
                throw new LlmGatewayException(LlmErrorCode.MALFORMED_RESPONSE,
                        "工具调用缺少 id 或 name");
            }
            return new ToolCallBlock(id, name, parseArguments(mapper));
        }

        private Map<String, Object> parseArguments(ObjectMapper mapper) {
            if (arguments.isEmpty()) {
                return Map.of();
            }
            try {
                JsonNode node = mapper.readTree(arguments.toString());
                if (node == null || node.isNull()) {
                    return Map.of();
                }
                if (!node.isObject()) {
                    throw new LlmGatewayException(LlmErrorCode.MALFORMED_RESPONSE,
                            "工具参数必须是 JSON 对象: " + name);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> values = mapper.convertValue(node, Map.class);
                return Map.copyOf(values);
            } catch (LlmGatewayException error) {
                throw error;
            } catch (Exception error) {
                throw new LlmGatewayException(LlmErrorCode.MALFORMED_RESPONSE,
                        "工具参数不是合法 JSON: " + name, error);
            }
        }
    }
}
