package com.piagent.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

class ModelProfileTest {

    private final ModelEndpoint endpoint = ModelEndpoint.fake("fake-coder");

    @Test
    void createStartsEnabledAtVersionOne() {
        ModelProfile profile = ModelProfile.create(new ModelId("fake-default"), "Fake", endpoint);

        assertThat(profile.enabled()).isTrue();
        assertThat(profile.version()).isEqualTo(ModelProfile.INITIAL_VERSION);
        assertThat(profile.ref()).isEqualTo(new ModelRef(ProviderType.FAKE, "fake-coder"));
    }

    @Test
    void editsKeepVersionAndIdentity() {
        ModelProfile profile = ModelProfile.create(new ModelId("fake-default"), "Fake", endpoint);
        ModelEndpoint other = ModelEndpoint.fake("other-model");

        ModelProfile renamed = profile.rename(" 本地模型 ");
        ModelProfile updated = renamed.updateEndpoint(other);
        ModelProfile disabled = updated.disable();

        assertThat(renamed.displayName()).isEqualTo("本地模型");
        assertThat(updated.endpoint()).isEqualTo(other);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.id()).isEqualTo(profile.id());
        assertThat(disabled.version()).isEqualTo(profile.version());
        assertThat(disabled.enable().enabled()).isTrue();
    }

    @Test
    void withVersionRejectsRollback() {
        ModelProfile profile = ModelProfile.create(new ModelId("fake-default"), "Fake", endpoint);

        assertThat(profile.withVersion(3).version()).isEqualTo(3);
        assertThatThrownBy(() -> profile.withVersion(4).withVersion(3))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void rejectsBlankDisplayNameAndInvalidVersion() {
        assertThatThrownBy(() -> new ModelProfile(new ModelId("fake-default"), " ", endpoint, true, 1))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> new ModelProfile(new ModelId("fake-default"), "Fake", endpoint, true, 0))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
