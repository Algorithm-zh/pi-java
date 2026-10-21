package com.piagent.infrastructure.persistence.model;

import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.ProviderType;
import com.piagent.infrastructure.persistence.codec.ModelCapabilitiesCodec;
import java.time.Instant;

/**
 * `pi_model_profile` 行与 {@link ModelProfile} 聚合之间的转换。
 *
 * <p>审计时间列不属于聚合状态，由仓储在写入时提供；读取时忽略。</p>
 */
public final class ModelProfilePoConverter {

    private final ModelCapabilitiesCodec capabilitiesCodec;

    /**
     * 创建转换器。
     *
     * @param capabilitiesCodec 能力编解码
     */
    public ModelProfilePoConverter(ModelCapabilitiesCodec capabilitiesCodec) {
        this.capabilitiesCodec = capabilitiesCodec;
    }

    /**
     * 聚合转持久化对象。
     *
     * @param profile 模型档案
     * @param now 写入时间
     * @return 持久化对象
     */
    public ModelProfilePO toPo(ModelProfile profile, Instant now) {
        ModelProfilePO po = new ModelProfilePO();
        po.setId(profile.id().value());
        po.setDisplayName(profile.displayName());
        po.setProvider(profile.endpoint().provider().name());
        po.setBaseUrl(profile.endpoint().baseUrl());
        po.setApiKeyEnv(profile.endpoint().apiKeyEnv());
        po.setModelName(profile.endpoint().modelName());
        po.setContextWindow(profile.endpoint().contextWindow());
        po.setMaxOutputTokens(profile.endpoint().maxOutputTokens());
        po.setCapabilitiesJson(capabilitiesCodec.encode(profile.endpoint().capabilities()));
        po.setEnabled(profile.enabled());
        po.setVersion(profile.version());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }

    /**
     * 持久化对象转聚合。
     *
     * @param po 持久化对象
     * @return 模型档案
     */
    public ModelProfile toDomain(ModelProfilePO po) {
        ModelCapabilities capabilities = capabilitiesCodec.decode(po.getCapabilitiesJson());
        ModelEndpoint endpoint = new ModelEndpoint(
                ProviderType.valueOf(po.getProvider()),
                po.getBaseUrl(),
                po.getApiKeyEnv(),
                po.getModelName(),
                po.getContextWindow(),
                po.getMaxOutputTokens(),
                capabilities);
        return new ModelProfile(new ModelId(po.getId()), po.getDisplayName(), endpoint,
                po.isEnabled(), po.getVersion());
    }
}
