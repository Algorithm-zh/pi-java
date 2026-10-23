package com.piagent.trigger.http.model;

import com.piagent.domain.model.ModelEndpoint;
import java.util.function.Function;

/**
 * 模型密钥环境变量状态。
 *
 * <p>只回答“是否已配置”，绝不读取或返回变量内容。Fake provider 不需要密钥，
 * 因此始终视为已就绪；这样前端可以用同一个字段判断档案能否直接运行。</p>
 */
public final class ApiKeyStatus {

    private ApiKeyStatus() {
    }

    /**
     * 使用进程环境判断密钥是否已配置。
     *
     * @param endpoint 模型端点
     * @return true 表示无需密钥或环境变量已设置且非空
     */
    public static boolean configured(ModelEndpoint endpoint) {
        return configured(endpoint, System::getenv);
    }

    /**
     * 使用自定义环境读取函数判断，便于单元测试不依赖真实进程环境。
     *
     * @param endpoint 模型端点
     * @param environment 环境变量读取函数
     * @return 是否已配置
     */
    static boolean configured(ModelEndpoint endpoint, Function<String, String> environment) {
        if (!endpoint.requiresApiKey()) {
            return true;
        }
        String value = environment.apply(endpoint.apiKeyEnv());
        return value != null && !value.isBlank();
    }
}
