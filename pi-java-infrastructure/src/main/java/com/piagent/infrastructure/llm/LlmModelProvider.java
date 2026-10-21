package com.piagent.infrastructure.llm;

import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmStream;

/**
 * provider 适配器的内部 SPI。
 *
 * <p>只属于基础设施层；领域层只看见 {@link com.piagent.domain.model.llm.LlmGateway}。
 * 新增 provider 时实现本接口并注册为 Spring Bean，路由网关会自动发现。</p>
 */
public interface LlmModelProvider {

    /**
     * 是否支持指定 provider。
     *
     * @param provider 提供方类型
     * @return true 表示支持
     */
    boolean supports(ProviderType provider);

    /**
     * 发起流式调用。
     *
     * @param request 请求
     * @param context 上下文
     * @return 流
     */
    LlmStream stream(LlmRequest request, LlmCallContext context);
}
