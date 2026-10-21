package com.piagent.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.app.PiJavaApplication;
import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 + Flyway + MyBatis 的模型档案持久化集成测试。
 *
 * <p>覆盖往返、启停过滤、乐观锁与能力 JSON 持久化；不涉及真实 provider。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
class ModelProfilePersistenceIntegrationTest {

    @Autowired
    private ModelProfileRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM pi_model_profile");
    }

    @Test
    void roundTripPreservesEndpointAndCapabilities() {
        ModelProfile profile = profile("fake-default", new ModelCapabilities(true, true, true));
        repository.insert(profile);

        ModelProfile loaded = repository.findById(profile.id()).orElseThrow();

        assertThat(loaded).isEqualTo(profile);
        assertThat(loaded.endpoint().capabilities().thinking()).isTrue();
        assertThat(loaded.endpoint().apiKeyEnv()).isEmpty();
    }

    @Test
    void findsAllAndOnlyEnabledProfiles() {
        repository.insert(profile("fake-a", ModelCapabilities.defaults()));
        ModelProfile disabled = profile("fake-b", ModelCapabilities.defaults()).disable();
        repository.insert(disabled);

        assertThat(repository.findAll()).extracting(item -> item.id().value())
                .containsExactly("fake-a", "fake-b");
        assertThat(repository.findEnabled()).extracting(item -> item.id().value())
                .containsExactly("fake-a");
    }

    @Test
    void updateUsesOptimisticLock() {
        ModelProfile profile = profile("fake-default", ModelCapabilities.defaults());
        repository.insert(profile);

        repository.update(profile.rename("更新后"));

        ModelProfile loaded = repository.findById(profile.id()).orElseThrow();
        assertThat(loaded.displayName()).isEqualTo("更新后");
        assertThat(loaded.version()).isEqualTo(2);
        assertThatThrownBy(() -> repository.update(profile.rename("过期写入")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    void missingProfileReturnsEmpty() {
        assertThat(repository.findById(new ModelId("missing"))).isEmpty();
    }

    @Test
    void openAiEndpointKeepsOnlyEnvironmentVariableName() {
        ModelEndpoint endpoint = ModelEndpoint.openAiCompatible("https://api.deepseek.com/v1",
                "DEEPSEEK_API_KEY", "deepseek-chat", 64_000, 8_000);
        ModelProfile profile = ModelProfile.create(new ModelId("deepseek-chat"), "DeepSeek", endpoint);
        repository.insert(profile);

        ModelProfile loaded = repository.findById(new ModelId("deepseek-chat")).orElseThrow();
        assertThat(loaded.endpoint().provider()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
        assertThat(loaded.endpoint().apiKeyEnv()).isEqualTo("DEEPSEEK_API_KEY");
    }

    private ModelProfile profile(String id, ModelCapabilities capabilities) {
        ModelEndpoint endpoint = new ModelEndpoint(ProviderType.FAKE, "", "", "fake-coder",
                16_000, 2_000, capabilities);
        return ModelProfile.create(new ModelId(id), "Fake " + id, endpoint);
    }
}
