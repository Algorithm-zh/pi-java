package com.piagent.application.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.session.command.ChangeSessionModelCommand;
import com.piagent.application.session.command.RenameSessionCommand;
import com.piagent.application.testsupport.InMemoryModelProfileRepository;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.service.ModelSelectionPolicy;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 会话设置用例测试：重命名、模型切换与归档。
 */
class SessionSettingsApplicationServiceTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final Path WORKSPACE = Path.of(System.getProperty("java.io.tmpdir"), "pi-test-workspace");

    private InMemorySessionRepository sessions;
    private InMemoryModelProfileRepository models;
    private SessionSettingsApplicationService service;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionRepository();
        models = new InMemoryModelProfileRepository();
        service = new SessionSettingsApplicationService(sessions, models, new ModelSelectionPolicy());
        session = ChatSession.createRoot(SESSION_ID, "旧标题",
                SessionConfig.forWorkspace(WORKSPACE, "fake-demo"));
        sessions.insert(session);
        models.insert(ModelProfile.create(new ModelId("fake-demo"), "Fake Demo",
                ModelEndpoint.fake("fake")));
        models.insert(ModelProfile.create(new ModelId("thinker"), "Thinker",
                new ModelEndpoint(com.piagent.domain.model.ProviderType.FAKE,
                        "", "", "thinker", 32_000, 4_000,
                        new ModelCapabilities(true, true, true))));
        models.insert(ModelProfile.create(new ModelId("real"), "Real",
                new ModelEndpoint(com.piagent.domain.model.ProviderType.OPENAI_COMPATIBLE,
                        "https://api.example.com", "EXAMPLE_API_KEY", "real-model", 32_000, 4_000,
                        ModelCapabilities.defaults())).disable());
    }

    @Test
    void renamesSessionAndIncrementsVersion() {
        ChatSession updated = service.rename(new RenameSessionCommand(SESSION_ID, "新标题"));

        assertThat(updated.title()).isEqualTo("新标题");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(sessions.findById(SESSION_ID).orElseThrow().title()).isEqualTo("新标题");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> service.rename(new RenameSessionCommand(SESSION_ID, " ")))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void switchesModelAndKeepsThinkingLevelWhenOmitted() {
        ChatSession updated = service.changeModel(
                new ChangeSessionModelCommand(SESSION_ID, "fake-demo", null));

        assertThat(updated.config().modelProfileId()).isEqualTo("fake-demo");
        assertThat(updated.config().thinkingLevel()).isEqualTo(ThinkingLevel.OFF);
        assertThat(updated.version()).isEqualTo(2);
    }

    @Test
    void switchesThinkingLevelExplicitly() {
        ChatSession updated = service.changeModel(
                new ChangeSessionModelCommand(SESSION_ID, "thinker", ThinkingLevel.HIGH));

        assertThat(updated.config().thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    void rejectsThinkingLevelUnsupportedByModel() {
        assertThatThrownBy(() -> service.changeModel(
                new ChangeSessionModelCommand(SESSION_ID, "fake-demo", ThinkingLevel.HIGH)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.MODEL_CAPABILITY_MISSING));
    }

    @Test
    void rejectsModelSwitchWhenCurrentThinkingLevelIsUnsupported() {
        service.changeModel(new ChangeSessionModelCommand(SESSION_ID, "thinker", ThinkingLevel.HIGH));

        assertThatThrownBy(() -> service.changeModel(
                new ChangeSessionModelCommand(SESSION_ID, "fake-demo", null)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.MODEL_CAPABILITY_MISSING));
    }

    @Test
    void rejectsUnknownOrDisabledModel() {
        assertThatThrownBy(() -> service.changeModel(
                new ChangeSessionModelCommand(SESSION_ID, "missing", null)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.MODEL_NOT_FOUND));

        assertThatThrownBy(() -> service.changeModel(
                new ChangeSessionModelCommand(SESSION_ID, "real", null)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.MODEL_DISABLED));
    }

    @Test
    void rejectsModelSwitchWhileRunning() {
        ChatSession running = ChatSession.restore(session.identity(), session.title(),
                session.config(), SessionStatus.RUNNING, null, session.version());
        sessions = new InMemorySessionRepository();
        sessions.insert(running);
        service = new SessionSettingsApplicationService(sessions, models, new ModelSelectionPolicy());

        assertThatThrownBy(() -> service.changeModel(
                new ChangeSessionModelCommand(SESSION_ID, "fake-demo", null)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void archivesIdleSession() {
        ChatSession archived = service.archive(SESSION_ID);

        assertThat(archived.status()).isEqualTo(SessionStatus.ARCHIVED);
        assertThat(archived.version()).isEqualTo(2);
        assertThat(sessions.findById(SESSION_ID).orElseThrow().status())
                .isEqualTo(SessionStatus.ARCHIVED);
    }

    @Test
    void deletesIdleSession() {
        service.delete(SESSION_ID);

        assertThat(sessions.findById(SESSION_ID)).isEmpty();
    }

    @Test
    void rejectsDeleteWhileRunning() {
        ChatSession running = ChatSession.restore(session.identity(), session.title(),
                session.config(), SessionStatus.RUNNING, null, session.version());
        sessions = new InMemorySessionRepository();
        sessions.insert(running);
        service = new SessionSettingsApplicationService(sessions, models, new ModelSelectionPolicy());

        assertThatThrownBy(() -> service.delete(SESSION_ID))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
        assertThat(sessions.findById(SESSION_ID)).isPresent();
    }

    @Test
    void unknownSessionIsRejected() {
        SessionId missing = SessionId.parse("01TST000000000000000000099");

        assertThatThrownBy(() -> service.archive(missing))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.SESSION_NOT_FOUND));
    }
}
