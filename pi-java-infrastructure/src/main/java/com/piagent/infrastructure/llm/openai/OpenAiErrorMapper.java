package com.piagent.infrastructure.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;

/**
 * OpenAI-compatible HTTP 错误到领域错误码的映射。
 *
 * <p>只提取 provider 返回的 error.message 并截断，不读取或记录请求头。</p>
 */
public final class OpenAiErrorMapper {

    private static final int MAX_DETAIL_LENGTH = 240;

    private final ObjectMapper mapper;

    /**
     * 创建映射器。
     *
     * @param mapper Jackson 映射器
     */
    public OpenAiErrorMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 映射 HTTP 错误。
     *
     * @param status HTTP 状态
     * @param body 响应体
     * @return 网关异常
     */
    public LlmGatewayException map(int status, String body) {
        String detail = detailOf(body);
        String message = "LLM 请求失败 (HTTP " + status + ")" + (detail.isEmpty() ? "" : ": " + detail);
        return new LlmGatewayException(codeOf(status, body), message);
    }

    private LlmErrorCode codeOf(int status, String body) {
        if (status == 401 || status == 403) {
            return LlmErrorCode.AUTHENTICATION;
        }
        if (status == 408) {
            return LlmErrorCode.TIMEOUT;
        }
        if (status == 429) {
            return LlmErrorCode.RATE_LIMITED;
        }
        if (status >= 500) {
            return LlmErrorCode.SERVER_ERROR;
        }
        if (status == 400 && looksLikeContextOverflow(body)) {
            return LlmErrorCode.CONTEXT_OVERFLOW;
        }
        return LlmErrorCode.INVALID_REQUEST;
    }

    private boolean looksLikeContextOverflow(String body) {
        String lower = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("context_length_exceeded")
                || (lower.contains("context") && lower.contains("token") && lower.contains("exceed"));
    }

    private String detailOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(body);
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) {
                return truncate(message);
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体按纯文本处理，下面统一截断。
        }
        return truncate(body.trim());
    }

    private String truncate(String value) {
        return value.length() <= MAX_DETAIL_LENGTH ? value : value.substring(0, MAX_DETAIL_LENGTH) + "...";
    }
}
