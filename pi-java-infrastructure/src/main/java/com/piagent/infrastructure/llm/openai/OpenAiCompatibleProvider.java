package com.piagent.infrastructure.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.infrastructure.llm.LlmModelProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;

/**
 * OpenAI-compatible provider（DeepSeek / OpenAI / Qwen / Ollama 等）。
 *
 * <p>使用 JDK HttpClient 手写 SSE，不引入 Spring AI。密钥只从档案声明的
 * 环境变量读取，并在发送瞬间放入 Authorization 头；缺失时返回
 * {@link LlmErrorCode#MISSING_API_KEY}，不发起网络请求。</p>
 */
public final class OpenAiCompatibleProvider implements LlmModelProvider {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper mapper;
    private final Function<String, String> environment;
    private final HttpClient httpClient;
    private final OpenAiRequestMapper requestMapper;
    private final OpenAiErrorMapper errorMapper;

    /**
     * 创建 provider。
     *
     * @param mapper Jackson 映射器
     * @param imageBytes 图片字节读取器
     */
    public OpenAiCompatibleProvider(ObjectMapper mapper, AttachmentBytesLoader imageBytes) {
        this(mapper, System::getenv, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), imageBytes);
    }

    OpenAiCompatibleProvider(ObjectMapper mapper, Function<String, String> environment,
            HttpClient httpClient) {
        this(mapper, environment, httpClient, attachmentId -> null);
    }

    OpenAiCompatibleProvider(ObjectMapper mapper, Function<String, String> environment,
            HttpClient httpClient, AttachmentBytesLoader imageBytes) {
        this.mapper = mapper;
        this.environment = environment;
        this.httpClient = httpClient;
        this.requestMapper = new OpenAiRequestMapper(mapper, imageBytes);
        this.errorMapper = new OpenAiErrorMapper(mapper);
    }

    @Override
    public boolean supports(ProviderType provider) {
        return provider == ProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public LlmStream stream(LlmRequest request, LlmCallContext context) {
        String apiKey = resolveApiKey(request);
        ToolNameCodec names = ToolNameCodec.of(request.tools());
        String json = requestMapper.toJson(request, names);
        URI uri = URI.create(resolveUrl(request));
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(context.timeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("User-Agent", "pi-java/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return new OpenAiEventStream(new OpenAiCall(httpClient, httpRequest, context, mapper,
                errorMapper, names));
    }

    private String resolveApiKey(LlmRequest request) {
        String variable = request.profile().endpoint().apiKeyEnv();
        if (variable == null || variable.isBlank()) {
            throw new LlmGatewayException(LlmErrorCode.MISSING_API_KEY,
                    "模型档案未配置 apiKeyEnv: " + request.profile().id());
        }
        String value = environment.apply(variable);
        if (value == null || value.isBlank()) {
            throw new LlmGatewayException(LlmErrorCode.MISSING_API_KEY,
                    "环境变量未设置: " + variable);
        }
        return value;
    }

    private String resolveUrl(LlmRequest request) {
        String baseUrl = request.profile().endpoint().baseUrl();
        return baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";
    }
}
