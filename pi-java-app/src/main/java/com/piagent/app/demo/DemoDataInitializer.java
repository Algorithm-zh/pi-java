package com.piagent.app.demo;

import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.port.ModelProfileRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Demo profile 的幂等种子数据。
 *
 * <p>只做一件事：确保存在一个 {@code fake-demo} 模型档案，让 Web UI 新建会话时
 * 有可选模型、无需任何 API Key 即可跑通整条链路。已存在时不覆盖用户修改。</p>
 */
@Component
@Profile("demo")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataInitializer.class);

    /** demo 模型档案 ID，Web UI 与文档共用。 */
    public static final String DEMO_MODEL_ID = "fake-demo";

    private final ModelProfileRepository models;

    /**
     * 创建初始化器。
     *
     * @param models 模型档案仓储
     */
    public DemoDataInitializer(ModelProfileRepository models) {
        this.models = models;
    }

    @Override
    public void run(ApplicationArguments args) {
        ModelId id = new ModelId(DEMO_MODEL_ID);
        Optional<ModelProfile> existing = models.findById(id);
        if (existing.isPresent()) {
            upgradeVision(existing.get());
            return;
        }
        models.insert(ModelProfile.create(id, "Fake Demo（离线）", ModelEndpoint.fake(DEMO_MODEL_ID)));
        LOG.info("已创建 demo 模型档案: {}", DEMO_MODEL_ID);
    }

    /**
     * 旧版本创建的 demo 档案没有 vision 能力标记；这里只补齐该标记，
     * 保留用户对显示名、启用状态等其他字段的修改。
     */
    private void upgradeVision(ModelProfile profile) {
        ModelEndpoint endpoint = profile.endpoint();
        if (endpoint.provider() != ProviderType.FAKE || endpoint.capabilities().vision()) {
            return;
        }
        ModelEndpoint upgraded = new ModelEndpoint(endpoint.provider(), endpoint.baseUrl(),
                endpoint.apiKeyEnv(), endpoint.modelName(), endpoint.contextWindow(),
                endpoint.maxOutputTokens(), ModelCapabilities.demo());
        models.update(profile.updateEndpoint(upgraded));
        LOG.info("已为 demo 模型档案补上图片能力: {}", DEMO_MODEL_ID);
    }
}
