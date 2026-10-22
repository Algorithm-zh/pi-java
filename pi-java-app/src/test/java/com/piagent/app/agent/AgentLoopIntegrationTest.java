package com.piagent.app.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Agent Loop 的端到端集成测试。
 *
 * <p>使用 demo 模式的 Fake provider、真实 H2 仓储与工作区工具，验证
 * “用户消息 → 模型 → 工具调用 → 工具结果 → 模型 → RUN_SUMMARY”整条链路。
 * SSE 传输层不在本测试范围内，由触发层手工与浏览器验证覆盖。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = "pi.fake.demo-mode=true")
@ActiveProfiles("test")
class AgentLoopIntegrationTest {

    private static final String MODEL_ID = "loop-test-fake";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private AgentLoopService agentLoop;

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEntryStore entryStore;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/agent-loop-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("hello.txt"), "你好，pi-java\n第二行");
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Loop Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void readCommandRunsToolAndWritesSummary() {
        ChatSession session = createSession("工具链路");

        RunHandle handle = agentLoop.start(
                new SendMessageCommand(session.identity().id(), "/read hello.txt"));
        AgentRun run = agentLoop.awaitTerminal(handle.runId(), TIMEOUT);

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.stopReason()).isEqualTo(RunStopReason.STOP);
        assertThat(run.turns()).isEqualTo(2);
        assertThat(run.toolCalls()).isEqualTo(1);

        List<SessionEntry> entries = currentBranch(session);
        assertThat(entries).extracting(SessionEntry::type).containsExactly(
                EntryType.MESSAGE, EntryType.MESSAGE, EntryType.MESSAGE,
                EntryType.MESSAGE, EntryType.RUN_SUMMARY);
        assertThat(agentLoop.findActive(session.identity().id())).isEmpty();

        List<AgentMessage> messages = messages(entries);
        assertThat(messages.get(1)).isInstanceOfSatisfying(AssistantMessage.class, assistant -> {
            assertThat(assistant.stopReason()).isEqualTo(AssistantStopReason.TOOL_USE);
            assertThat(assistant.content()).filteredOn(ToolCallBlock.class::isInstance)
                    .hasSize(1);
        });
        ToolCallBlock call = (ToolCallBlock) ((AssistantMessage) messages.get(1)).content()
                .stream().filter(ToolCallBlock.class::isInstance).findFirst().orElseThrow();
        assertThat(messages.get(2)).isInstanceOfSatisfying(ToolResultMessage.class, result -> {
            assertThat(result.toolCallId()).isEqualTo(call.id());
            assertThat(result.toolName()).isEqualTo("read");
            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("你好，pi-java");
        });
        assertThat(messages.get(3)).isInstanceOfSatisfying(AssistantMessage.class,
                assistant -> {
                    assertThat(assistant.stopReason()).isEqualTo(AssistantStopReason.STOP);
                    assertThat(text(assistant)).contains("已返回结果").contains("你好，pi-java");
                });

        RunSummaryPayload summary = (RunSummaryPayload) entries.getLast().payload();
        assertThat(summary.runId()).isEqualTo(handle.runId().toString());
        assertThat(summary.stopReason()).isEqualTo(RunStopReason.STOP.name());
        assertThat(summary.turns()).isEqualTo(2);
        assertThat(summary.toolCalls()).isEqualTo(1);

        ChatSession reloaded = requireSession(session);
        assertThat(reloaded.status()).isEqualTo(SessionStatus.IDLE);
        assertThat(reloaded.leafEntryId()).isEqualTo(entries.getLast().id());
    }

    @Test
    void plainTextFinishesInSingleTurn() {
        ChatSession session = createSession("纯文本");

        RunHandle handle = agentLoop.start(
                new SendMessageCommand(session.identity().id(), "你好"));
        AgentRun run = agentLoop.awaitTerminal(handle.runId(), TIMEOUT);

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.turns()).isEqualTo(1);
        assertThat(run.toolCalls()).isZero();

        List<SessionEntry> entries = currentBranch(session);
        assertThat(entries).extracting(SessionEntry::type)
                .containsExactly(EntryType.MESSAGE, EntryType.MESSAGE, EntryType.RUN_SUMMARY);
        assertThat(text(messages(entries).get(1))).contains("我收到：你好");
        assertThat(requireSession(session).status()).isEqualTo(SessionStatus.IDLE);
    }

    private ChatSession createSession(String title) {
        return sessions.createSession(
                new CreateSessionCommand(title, workspace, MODEL_ID, ThinkingLevel.OFF));
    }

    private ChatSession requireSession(ChatSession session) {
        return sessionRepository.findById(session.identity().id()).orElseThrow();
    }

    private List<SessionEntry> currentBranch(ChatSession session) {
        ChatSession reloaded = requireSession(session);
        return entryStore.loadBranch(session.identity().id(), reloaded.leafEntryId());
    }

    private List<AgentMessage> messages(List<SessionEntry> entries) {
        return entries.stream()
                .filter(entry -> entry.payload() instanceof MessagePayload)
                .map(entry -> ((MessagePayload) entry.payload()).message())
                .toList();
    }

    private String text(AgentMessage message) {
        List<?> content;
        if (message instanceof AssistantMessage assistant) {
            content = assistant.content();
        } else if (message instanceof ToolResultMessage result) {
            content = result.content();
        } else {
            return "";
        }
        return content.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .reduce("", String::concat);
    }
}
