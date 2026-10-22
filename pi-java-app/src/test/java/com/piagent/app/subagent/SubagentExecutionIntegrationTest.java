package com.piagent.app.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.application.subagent.SubagentService;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.Origin;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.subagent.SubagentMode;
import com.piagent.domain.subagent.SubagentRequest;
import com.piagent.domain.subagent.SubagentResult;
import com.piagent.domain.tool.port.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 子代理真实执行测试：使用 Fake demo 模型跑完整的一轮子 run。
 *
 * <p>与 {@code SubagentEndToEndIntegrationTest} 的区别：那边只验证装配与树结构，
 * 这里让子代理真正启动 Agent Loop、调用工具、产出结论，验证「同步等待 + 结果聚合」
 * 这条主链路是通的。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class, properties = "pi.fake.demo-mode=true")
@ActiveProfiles("test")
class SubagentExecutionIntegrationTest {

    private static final String MODEL_ID = "subagent-run-fake";

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEntryStore entries;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private SubagentService subagents;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM pi_approval_request");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/subagent-run-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("note.txt"), "子代理读取到的内容");
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Subagent Run Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void subagentCompletesAndReturnsConclusion() {
        ChatSession parent = createSession("真实子代理");
        // Fake demo 模型按“最后一条用户消息”匹配指令；子代理提示词带前缀，
        // 因此这里断言“子 run 正常完成”而不是“读到了具体文件内容”。
        SubagentRequest request = new SubagentRequest(SubagentMode.SPAWN,
                "总结工作区", null, List.of("read"), 60);

        SubagentResult result = subagents.delegate(parent, request, toolRegistry.descriptors());

        assertThat(result.stopReason()).isEqualTo(RunStopReason.STOP);
        assertThat(result.isError()).isFalse();
        assertThat(result.turns()).isGreaterThanOrEqualTo(1);
        assertThat(result.summary()).isNotBlank();

        ChatSession child = sessionRepository.findById(result.childSessionId()).orElseThrow();
        assertThat(child.identity().origin()).isEqualTo(Origin.SUBAGENT);
        assertThat(child.status())
                .isEqualTo(com.piagent.domain.session.model.SessionStatus.IDLE);
        // 子 run 必须留下 RUN_SUMMARY，父模型才能从结果里看到 stopReason
        assertThat(entries.loadBranch(child.identity().id(), child.leafEntryId()))
                .anyMatch(entry -> entry.type()
                        == com.piagent.domain.session.model.EntryType.RUN_SUMMARY);
    }

    @Test
    void subagentRespectsToolWhitelist() {
        ChatSession parent = createSession("白名单");
        SubagentRequest request = new SubagentRequest(SubagentMode.SPAWN, "列出目录",
                null, List.of("ls"), 60);

        SubagentResult result = subagents.delegate(parent, request, toolRegistry.descriptors());

        ChatSession child = sessionRepository.findById(result.childSessionId()).orElseThrow();
        // 白名单只有 ls：子代理不会拿到 read，也就不会读到文件正文
        assertThat(toolResultsOf(child)).noneMatch(text -> text.contains("子代理读取到的内容"));
        assertThat(result.stopReason()).isNotNull();
    }

    @Test
    void parentSessionRemainsIdleAndHasNoChildEntries() {
        ChatSession parent = createSession("父会话不受影响");

        subagents.delegate(parent, new SubagentRequest(SubagentMode.SPAWN,
                "你好", null, List.of(), 60), toolRegistry.descriptors());

        ChatSession reloaded = sessionRepository.findById(parent.identity().id()).orElseThrow();
        assertThat(reloaded.status())
                .isEqualTo(com.piagent.domain.session.model.SessionStatus.IDLE);
        assertThat(reloaded.leafEntryId()).as("子代理不应写入父会话").isNull();
    }

    private List<String> toolResultsOf(ChatSession session) {
        if (session.leafEntryId() == null) {
            return List.of();
        }
        return entries.loadBranch(session.identity().id(), session.leafEntryId()).stream()
                .map(SessionEntry::payload)
                .filter(MessagePayload.class::isInstance)
                .map(MessagePayload.class::cast)
                .map(MessagePayload::message)
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .flatMap(message -> message.content().stream())
                .filter(com.piagent.domain.session.model.message.TextBlock.class::isInstance)
                .map(com.piagent.domain.session.model.message.TextBlock.class::cast)
                .map(com.piagent.domain.session.model.message.TextBlock::text)
                .toList();
    }

    private ChatSession createSession(String title) {
        return sessions.createSession(new CreateSessionCommand(title, workspace, MODEL_ID,
                ThinkingLevel.OFF));
    }
}
