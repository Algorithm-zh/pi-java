package com.piagent.application.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.model.command.CreateModelProfileCommand;
import com.piagent.application.model.command.UpdateModelProfileCommand;
import com.piagent.application.testsupport.InMemoryModelProfileRepository;
import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.service.ModelSelectionPolicy;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelProfileApplicationServiceTest {

    private InMemoryModelProfileRepository repository;
    private ModelProfileApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryModelProfileRepository();
        service = new ModelProfileApplicationService(repository, new ModelSelectionPolicy());
    }

    @Test
    void createsEnabledProfile() {
        ModelProfile profile = service.create(new CreateModelProfileCommand("fake-default", "Fake", fakeEndpoint()));

        assertThat(profile.id().value()).isEqualTo("fake-default");
        assertThat(profile.enabled()).isTrue();
        assertThat(profile.version()).isEqualTo(1);
        assertThat(service.find("fake-default")).isEqualTo(profile);
    }

    @Test
    void rejectsDuplicateId() {
        service.create(new CreateModelProfileCommand("fake-default", "Fake", fakeEndpoint()));

        assertCode(() -> service.create(new CreateModelProfileCommand("fake-default", "Other", fakeEndpoint())),
                DomainErrorCode.MODEL_ALREADY_EXISTS);
    }

    @Test
    void updatesNameAndEndpointWithNewVersion() {
        service.create(new CreateModelProfileCommand("fake-default", "Fake", fakeEndpoint()));

        ModelProfile updated = service.update(new UpdateModelProfileCommand("fake-default", "本地模型",
                ModelEndpoint.openAiCompatible("https://api.deepseek.com", "DEEPSEEK_API_KEY",
                        "deepseek-chat", 64_000, 8_000)));

        assertThat(updated.displayName()).isEqualTo("本地模型");
        assertThat(updated.endpoint().provider()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
        assertThat(updated.endpoint().apiKeyEnv()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(service.find("fake-default")).isEqualTo(updated);
    }

    @Test
    void updateMissingProfileFails() {
        assertCode(() -> service.update(new UpdateModelProfileCommand("missing", "Missing", fakeEndpoint())),
                DomainErrorCode.MODEL_NOT_FOUND);
    }

    @Test
    void togglesEnabledIdempotently() {
        service.create(new CreateModelProfileCommand("fake-default", "Fake", fakeEndpoint()));

        ModelProfile disabled = service.disable("fake-default");
        ModelProfile disabledAgain = service.disable("fake-default");
        ModelProfile enabled = service.enable("fake-default");

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.version()).isEqualTo(2);
        assertThat(disabledAgain).isEqualTo(disabled);
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.version()).isEqualTo(3);
    }

    @Test
    void requireEnabledRejectsDisabledAndMissingProfiles() {
        service.create(new CreateModelProfileCommand("fake-default", "Fake", fakeEndpoint()));
        service.disable("fake-default");

        assertCode(() -> service.requireEnabled("fake-default"), DomainErrorCode.MODEL_DISABLED);
        assertCode(() -> service.requireEnabled("missing"), DomainErrorCode.MODEL_NOT_FOUND);
    }

    @Test
    void listsAllAndEnabledProfiles() {
        service.create(new CreateModelProfileCommand("fake-a", "A", fakeEndpoint()));
        service.create(new CreateModelProfileCommand("fake-b", "B", fakeEndpoint()));
        service.disable("fake-b");

        assertThat(service.listAll()).extracting(profile -> profile.id().value())
                .containsExactly("fake-a", "fake-b");
        assertThat(service.listEnabled()).extracting(profile -> profile.id().value())
                .containsExactly("fake-a");
        assertThat(service.findOptional("missing")).isEmpty();
    }

    @Test
    void rejectsUnknownProfileOnEnable() {
        assertCode(() -> service.enable("missing"), DomainErrorCode.MODEL_NOT_FOUND);
        assertCode(() -> service.disable("missing"), DomainErrorCode.MODEL_NOT_FOUND);
    }

    private ModelEndpoint fakeEndpoint() {
        return new ModelEndpoint(ProviderType.FAKE, "", "", "fake-coder", 16_000, 2_000,
                ModelCapabilities.defaults());
    }

    private void assertCode(Runnable action, DomainErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }
}
