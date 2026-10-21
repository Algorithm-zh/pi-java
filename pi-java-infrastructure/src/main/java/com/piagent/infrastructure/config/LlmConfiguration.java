package com.piagent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.llm.LlmGateway;
import com.piagent.infrastructure.llm.LlmModelProvider;
import com.piagent.infrastructure.llm.RoutingLlmGateway;
import com.piagent.infrastructure.llm.fake.FakeLlmProvider;
import com.piagent.infrastructure.llm.openai.OpenAiCompatibleProvider;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 适配器装配。
 *
 * <p>Fake provider 永远注册，保证 demo 与测试无需外部服务；OpenAI-compatible
 * provider 只有在模型档案声明该 provider 时才会被路由到。</p>
 */
@Configuration
public class LlmConfiguration {

    /**
     * Fake provider。
     *
     * @return Fake provider
     */
    @Bean
    public FakeLlmProvider fakeLlmProvider() {
        return new FakeLlmProvider();
    }

    /**
     * OpenAI-compatible provider。
     *
     * @param mapper Jackson 映射器
     * @return OpenAI-compatible provider
     */
    @Bean
    public OpenAiCompatibleProvider openAiCompatibleProvider(ObjectMapper mapper) {
        return new OpenAiCompatibleProvider(mapper);
    }

    /**
     * 路由网关。
     *
     * @param providers 所有 provider 适配器
     * @return LLM 网关
     */
    @Bean
    public LlmGateway llmGateway(List<LlmModelProvider> providers) {
        return new RoutingLlmGateway(providers);
    }
}
