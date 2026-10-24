package com.piagent.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.trigger.cli.CliRunner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * CLI 端到端测试：真实 Spring 容器、H2、Fake demo 脚本与审批链路。
 */
@SpringBootTest(classes = PiJavaApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "pi.fake.demo-mode=true")
@ActiveProfiles("test")
class CliEndToEndIntegrationTest {

    private static final String MODEL_ID = "cli-e2e-fake";

    @Autowired
    private CliRunner runner;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM pi_approval_request");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/cli-e2e-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.deleteIfExists(workspace.resolve("approved.txt"));
        Files.deleteIfExists(workspace.resolve("denied.txt"));
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "CLI E2E Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void jsonRunCompletesAndKeepsStdoutParsable() throws Exception {
        CliResult result = run("", "run", "你好", "--cwd", workspace.toString(),
                "--model", MODEL_ID, "--json");

        assertThat(result.code()).isZero();
        JsonNode json = objectMapper.readTree(result.out());
        assertThat(json.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(json.get("stopReason").asText()).isEqualTo("STOP");
        assertThat(json.get("finalText").asText()).isNotBlank();
        assertThat(json.get("sessionId").asText()).hasSize(26);
    }

    @Test
    void approvedWriteCreatesFile() throws IOException {
        CliResult result = run("y\n", "run", "/write approved.txt 你好",
                "--cwd", workspace.toString(), "--model", MODEL_ID);

        assertThat(result.code()).isZero();
        assertThat(Files.readString(workspace.resolve("approved.txt"))).isEqualTo("你好");
        assertThat(result.out()).contains("[审批]").contains("APPROVED").contains("完成");
    }

    @Test
    void deniedWriteDoesNotCreateFile() {
        CliResult result = run("n\n", "run", "/write denied.txt 不要写",
                "--cwd", workspace.toString(), "--model", MODEL_ID);

        assertThat(result.code()).isEqualTo(3);
        assertThat(workspace.resolve("denied.txt")).doesNotExist();
        assertThat(result.out()).contains("DENIED").contains("有审批被拒绝");
    }

    @Test
    void missingApprovalInputDeniesByDefault() {
        CliResult result = run("", "run", "/write denied.txt 不要写",
                "--cwd", workspace.toString(), "--model", MODEL_ID);

        assertThat(result.code()).isEqualTo(3);
        assertThat(workspace.resolve("denied.txt")).doesNotExist();
        assertThat(result.out()).contains("无输入，默认拒绝");
    }

    @Test
    void jsonModeStillDeniesWithoutInputAndReportsFlag() throws Exception {
        CliResult result = run("", "run", "/write denied.txt 不要写",
                "--cwd", workspace.toString(), "--model", MODEL_ID, "--json");

        assertThat(result.code()).isEqualTo(3);
        JsonNode json = objectMapper.readTree(result.out());
        assertThat(json.get("approvalDenied").asBoolean()).isTrue();
        assertThat(result.err()).contains("批准执行?");
        assertThat(workspace.resolve("denied.txt")).doesNotExist();
    }

    @Test
    void listCommandsReadSharedState() throws Exception {
        CliResult run = run("", "run", "你好", "--cwd", workspace.toString(),
                "--model", MODEL_ID, "--json");
        String sessionId = objectMapper.readTree(run.out()).get("sessionId").asText();

        CliResult sessions = run("", "sessions", "list", "--cwd", workspace.toString());
        assertThat(sessions.code()).isZero();
        assertThat(sessions.out()).contains(sessionId);

        CliResult show = run("", "sessions", "show", sessionId);
        assertThat(show.code()).isZero();
        assertThat(show.out()).contains("用户:").contains("运行摘要");

        CliResult modelsOut = run("", "models", "list");
        assertThat(modelsOut.code()).isZero();
        assertThat(modelsOut.out()).contains(MODEL_ID).contains("FAKE");

        CliResult tools = run("", "tools", "list", "--cwd", workspace.toString());
        assertThat(tools.code()).isZero();
        assertThat(tools.out()).contains("write").contains("read");
    }

    @Test
    void chatSupportsMultipleTurnsAndTree() {
        CliResult result = run("你好\n/tree\n/exit\n", "chat", "--cwd", workspace.toString(),
                "--model", MODEL_ID);

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("你>").contains("用户:").contains("— 完成");
    }

    @Test
    void missingMessageIsUsageError() {
        CliResult result = run("", "run", "--cwd", workspace.toString(), "--model", MODEL_ID);

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("缺少任务文本");
    }

    @Test
    void rootHelpListsCommands() {
        CliResult result = run("", "--help");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("run").contains("chat").contains("sessions");
    }

    private CliResult run(String input, String... args) {
        ByteArrayInputStream stdin = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int code = runner.execute(args, stdin, new PrintWriter(stdout, true),
                new PrintWriter(stderr, true));
        return new CliResult(code, stdout.toString(), stderr.toString());
    }

    private record CliResult(int code, String out, String err) {
    }
}
