package com.piagent.app.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.ThinkingLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SSE 传输集成测试。
 *
 * <p>走完整 Spring MVC 栈：POST 建立流、异步运行 Fake 模型与真实读工具、
 * 终态后 emitter 完成，再校验 SSE 事件顺序与 {@code Last-Event-ID} 回放。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = "pi.fake.demo-mode=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RunSseIntegrationTest {

    private static final String MODEL_ID = "sse-test-fake";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern EVENT_ID =
            Pattern.compile("(?m)^id:([^:\\r\\n]+):(\\d+)$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionApplicationService sessions;

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
        workspace = Path.of("target/sse-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("hello.txt"), "SSE 你好");
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "SSE Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void streamsOrderedEventsAndReplaysAfterLastEventId() throws Exception {
        ChatSession session = sessions.createSession(
                new CreateSessionCommand("SSE 会话", workspace, MODEL_ID, ThinkingLevel.OFF));

        MvcResult streaming = mockMvc.perform(
                        post("/api/v1/sessions/{id}/messages/stream", session.identity().id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"/read hello.txt\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        streaming.getAsyncResult(ASYNC_TIMEOUT.toMillis());

        String body = mockMvc.perform(asyncDispatch(streaming))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).containsSubsequence(
                "event:run.started", "event:turn.started", "event:message.started",
                "event:message.delta", "event:message.completed", "event:tool.started",
                "event:tool.completed", "event:turn.started", "event:message.completed",
                "event:run.completed");
        assertThat(body).contains("\"toolName\":\"read\"", "SSE 你好");

        List<Long> seqs = seqsOf(body);
        assertThat(seqs).isNotEmpty();
        assertThat(seqs.getFirst()).isEqualTo(1);
        assertThat(seqs).isSortedAccordingTo(Long::compareTo).doesNotHaveDuplicates();
        String runId = runIdOf(body);

        MvcResult replaying = mockMvc.perform(get("/api/v1/runs/{runId}/events", runId)
                        .header("Last-Event-ID", runId + ":2"))
                .andExpect(request().asyncStarted())
                .andReturn();
        replaying.getAsyncResult(ASYNC_TIMEOUT.toMillis());

        String replay = mockMvc.perform(asyncDispatch(replaying))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(seqsOf(replay)).isNotEmpty().allMatch(seq -> seq > 2);
        assertThat(replay).contains("event:run.completed").doesNotContain("\"seq\":1,");
    }

    private static String runIdOf(String body) {
        Matcher matcher = EVENT_ID.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static List<Long> seqsOf(String body) {
        Matcher matcher = EVENT_ID.matcher(body);
        List<Long> seqs = new ArrayList<>();
        while (matcher.find()) {
            seqs.add(Long.parseLong(matcher.group(2)));
        }
        return seqs;
    }
}
