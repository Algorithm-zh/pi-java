package com.piagent.app.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.application.subagent.SubagentService;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.Origin;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.subagent.SubagentMode;
import com.piagent.domain.subagent.SubagentPolicy;
import com.piagent.domain.subagent.SubagentRequest;
import com.piagent.domain.subagent.SubagentResult;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.port.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 子代理的装配与端到端验证（真实 Spring 容器 + H2）。
 *
 * <p>验证：{@code subagent} 出现在工具目录且需要审批；用例服务真实创建的子会话
 * 在会话树中的 parent / origin / depth 正确落库；工具子集按深度收敛。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SubagentEndToEndIntegrationTest {

    private static final String MODEL_ID = "subagent-e2e-fake";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private SubagentService subagents;

    @Autowired
    private SubagentPolicy policy;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspace;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pi_approval_request");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/subagent-e2e-workspace").toAbsolutePath().normalize();
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Subagent E2E Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void subagentToolIsRegisteredAndNeedsApproval() throws Exception {
        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='subagent')].requiresApproval").value(true));
    }

    @Test
    void keepsSubagentToolForShallowSessionsAndDropsItAtDepthLimit() {
        ChatSession root = createSession("深度测试");
        List<ToolDescriptor> parentTools = toolRegistry.descriptors();

        assertThat(names(subagents.toolsFor(parentTools, root, request())))
                .as("用户会话派生的子代理仍可继续派生")
                .contains("subagent");

        ChatSession deep = root;
        for (int level = 0; level < policy.maxDepth(); level++) {
            deep = ChatSession.createChild(nextId(), deep, null, Origin.SUBAGENT,
                    "深层 " + level, deep.config());
            sessionRepository.insert(deep);
        }

        assertThat(names(subagents.toolsFor(parentTools, deep, request())))
                .as("达到深度上限后不再提供 subagent")
                .doesNotContain("subagent");
    }

    @Test
    void delegateCreatesChildSessionInsideSessionTree() {
        ChatSession parent = createSession("子代理派发");

        SubagentResult result = subagents.delegate(parent, request(), toolRegistry.descriptors());

        ChatSession child = sessionRepository.findById(result.childSessionId()).orElseThrow();
        assertThat(child.identity().parentSessionId()).isEqualTo(parent.identity().id());
        assertThat(child.identity().rootSessionId()).isEqualTo(parent.identity().id());
        assertThat(child.identity().depth()).isEqualTo(1);
        assertThat(child.identity().origin()).isEqualTo(Origin.SUBAGENT);
        assertThat(sessionRepository.findChildren(parent.identity().id()))
                .extracting(session -> session.identity().id())
                .contains(child.identity().id());
    }

    @Test
    void forkModeRecordsTheParentLeafAsStartPoint() {
        ChatSession parent = createSession("FORK 模式");
        SubagentRequest fork = new SubagentRequest(SubagentMode.FORK, "继承上下文", null,
                List.of(), null);

        SubagentResult result = subagents.delegate(parent, fork, toolRegistry.descriptors());

        ChatSession child = sessionRepository.findById(result.childSessionId()).orElseThrow();
        // 父会话此刻还没有任何 entry，leafEntryId 为 null；FORK 记录该值即可
        assertThat(child.identity().origin()).isEqualTo(Origin.SUBAGENT);
        assertThat(child.identity().forkFromEntryId()).isNull();
    }

    @Test
    void policyIsBoundFromConfiguration() {
        assertThat(policy.maxDepth()).isEqualTo(2);
        assertThat(policy.defaultTimeoutSeconds()).isEqualTo(300);
        assertThat(policy.maxTimeoutSeconds())
                .isLessThanOrEqualTo(SubagentPolicy.MAX_TIMEOUT_SECONDS);
    }

    private List<String> names(List<ToolDescriptor> tools) {
        return tools.stream().map(tool -> tool.name().toString()).toList();
    }

    private SessionId nextId() {
        return SessionId.newId(idGenerator);
    }

    private SubagentRequest request() {
        return new SubagentRequest(SubagentMode.SPAWN, "检查 README 是否存在", null, List.of(),
                null);
    }

    private ChatSession createSession(String title) {
        return sessions.createSession(new CreateSessionCommand(title, workspace, MODEL_ID,
                ThinkingLevel.OFF));
    }
}
