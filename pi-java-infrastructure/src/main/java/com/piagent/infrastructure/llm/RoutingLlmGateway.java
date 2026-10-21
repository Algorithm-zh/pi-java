package com.piagent.infrastructure.llm;

import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGateway;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按 provider 类型路由的 LLM 网关。
 *
 * <p>启动时冻结 provider 映射；重复实现同一 provider 视为装配错误。
 * 未实现的 provider（如 v0.1 的 ANTHROPIC）返回稳定错误码，而不是 500。</p>
 */
public final class RoutingLlmGateway implements LlmGateway {

    private final Map<ProviderType, LlmModelProvider> providers;

    /**
     * 创建路由网关。
     *
     * @param available 可用 provider 适配器
     */
    public RoutingLlmGateway(List<LlmModelProvider> available) {
        Map<ProviderType, LlmModelProvider> map = new EnumMap<>(ProviderType.class);
        for (ProviderType type : ProviderType.values()) {
            available.stream()
                    .filter(provider -> provider.supports(type))
                    .forEach(provider -> {
                        if (map.putIfAbsent(type, provider) != null) {
                            throw new IllegalStateException("provider 存在多个适配器: " + type);
                        }
                    });
        }
        this.providers = Map.copyOf(map);
    }

    @Override
    public LlmStream stream(LlmRequest request, LlmCallContext context) {
        ProviderType type = request.profile().endpoint().provider();
        LlmModelProvider provider = providers.get(type);
        if (provider == null) {
            throw new LlmGatewayException(LlmErrorCode.UNSUPPORTED_PROVIDER,
                    "暂不支持该 provider: " + type);
        }
        return provider.stream(request, context);
    }
}
