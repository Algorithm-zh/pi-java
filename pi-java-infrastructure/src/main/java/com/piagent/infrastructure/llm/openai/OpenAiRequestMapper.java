package com.piagent.infrastructure.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmTool;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.message.CompactionSummaryMessage;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;

/**
 * 把规范消息映射为 OpenAI-compatible Chat Completions 请求。
 *
 * <p>只发送文本、工具调用与工具结果；思考块不回传，因为 OpenAI-compatible
 * 协议没有统一的 reasoning 回传字段。压缩/分支摘要作为 user 消息注入上下文。</p>
 */
public final class OpenAiRequestMapper {

    private static final String SUMMARY_PREFIX = "[历史摘要]\n";

    private final ObjectMapper mapper;

    /**
     * 创建映射器。
     *
     * @param mapper Jackson 映射器
     */
    public OpenAiRequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 构造请求 JSON。
     *
     * @param request 规范请求
     * @return JSON
     */
    public String toJson(LlmRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.profile().endpoint().modelName());
        root.put("stream", true);
        root.putObject("stream_options").put("include_usage", true);
        root.put("max_tokens", request.profile().endpoint().maxOutputTokens());
        ArrayNode messages = root.putArray("messages");
        if (!request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        request.messages().forEach(message -> appendMessage(messages, message));
        appendTools(root, request.tools());
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException error) {
            throw new LlmGatewayException(LlmErrorCode.INVALID_REQUEST, "无法构造 LLM 请求 JSON", error);
        }
    }

    private void appendMessage(ArrayNode messages, AgentMessage message) {
        if (message instanceof UserMessage user) {
            messages.addObject().put("role", "user").put("content", textOf(user.content()));
        } else if (message instanceof AssistantMessage assistant) {
            appendAssistant(messages, assistant);
        } else if (message instanceof ToolResultMessage result) {
            messages.addObject().put("role", "tool")
                    .put("tool_call_id", result.toolCallId())
                    .put("content", textOf(result.content()));
        } else if (message instanceof CompactionSummaryMessage summary) {
            messages.addObject().put("role", "user").put("content", SUMMARY_PREFIX + summary.summary());
        } else if (message instanceof BranchSummaryMessage branch) {
            messages.addObject().put("role", "user").put("content", SUMMARY_PREFIX + branch.summary());
        }
    }

    private void appendAssistant(ArrayNode messages, AssistantMessage assistant) {
        String text = textOf(assistant.content());
        ArrayNode toolCalls = messages.addObject().put("role", "assistant").putArray("tool_calls");
        for (ContentBlock block : assistant.content()) {
            if (block instanceof ToolCallBlock call) {
                ObjectNode toolCall = toolCalls.addObject();
                toolCall.put("id", call.id());
                toolCall.put("type", "function");
                ObjectNode function = toolCall.putObject("function");
                function.put("name", call.name());
                function.put("arguments", argumentsJson(call));
            }
        }
        ObjectNode node = (ObjectNode) messages.get(messages.size() - 1);
        if (toolCalls.isEmpty()) {
            node.remove("tool_calls");
        }
        if (text.isBlank() && toolCalls.isEmpty()) {
            messages.remove(messages.size() - 1);
            return;
        }
        if (!text.isBlank()) {
            node.put("content", text);
        }
    }

    private void appendTools(ObjectNode root, java.util.List<LlmTool> tools) {
        if (tools.isEmpty()) {
            return;
        }
        ArrayNode array = root.putArray("tools");
        for (LlmTool tool : tools) {
            ObjectNode item = array.addObject();
            item.put("type", "function");
            ObjectNode function = item.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", schemaOf(tool));
        }
        root.put("tool_choice", "auto");
    }

    private JsonNode schemaOf(LlmTool tool) {
        try {
            return mapper.readTree(tool.inputSchemaJson());
        } catch (JsonProcessingException error) {
            throw new LlmGatewayException(LlmErrorCode.INVALID_REQUEST,
                    "工具 " + tool.name() + " 的 JSON Schema 不合法", error);
        }
    }

    private String argumentsJson(ToolCallBlock call) {
        try {
            return mapper.writeValueAsString(call.arguments());
        } catch (JsonProcessingException error) {
            throw new LlmGatewayException(LlmErrorCode.INVALID_REQUEST,
                    "工具 " + call.name() + " 的参数无法序列化", error);
        }
    }

    private String textOf(java.util.List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.text());
            }
        }
        return text.toString();
    }
}
