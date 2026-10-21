package com.piagent.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.model.service.ModelSelectionPolicy;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

class ModelSelectionPolicyTest {

    private final ModelSelectionPolicy policy = new ModelSelectionPolicy();

    @Test
    void enabledStreamingModelPasses() {
        policy.requireCompatible(profile(true, new ModelCapabilities(true, true, false)), true,
                ThinkingLevel.OFF);
    }

    @Test
    void disabledModelIsRejected() {
        assertCode(() -> policy.requireEnabled(profile(false, ModelCapabilities.defaults())),
                DomainErrorCode.MODEL_DISABLED);
    }

    @Test
    void streamingIsRequired() {
        assertCode(() -> policy.requireCompatible(
                        profile(true, new ModelCapabilities(false, true, false)), false, ThinkingLevel.OFF),
                DomainErrorCode.MODEL_CAPABILITY_MISSING);
    }

    @Test
    void toolsRequireCapabilityOnlyWhenToolsArePresent() {
        ModelProfile noTools = profile(true, new ModelCapabilities(true, false, false));

        policy.requireCompatible(noTools, false, ThinkingLevel.OFF);
        assertCode(() -> policy.requireCompatible(noTools, true, ThinkingLevel.OFF),
                DomainErrorCode.MODEL_CAPABILITY_MISSING);
    }

    @Test
    void thinkingRequiresCapabilityOnlyWhenEnabled() {
        ModelProfile noThinking = profile(true, new ModelCapabilities(true, true, false));

        policy.requireCompatible(noThinking, false, ThinkingLevel.OFF);
        policy.requireCompatible(noThinking, false, null);
        assertCode(() -> policy.requireCompatible(noThinking, false, ThinkingLevel.HIGH),
                DomainErrorCode.MODEL_CAPABILITY_MISSING);
    }

    private ModelProfile profile(boolean enabled, ModelCapabilities capabilities) {
        ModelEndpoint endpoint = new ModelEndpoint(ProviderType.FAKE, "", "", "fake-coder",
                16_000, 2_000, capabilities);
        ModelProfile profile = ModelProfile.create(new ModelId("fake-default"), "Fake", endpoint);
        return enabled ? profile : profile.disable();
    }

    private void assertCode(Runnable action, DomainErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }
}
