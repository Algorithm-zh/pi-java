package com.piagent.application.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.subagent.SubagentMode;
import com.piagent.domain.subagent.SubagentPolicy;
import com.piagent.domain.subagent.SubagentRequest;
import com.piagent.domain.subagent.SubagentResult;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 子代理用例服务测试。
 *
 * <p>执行服务用替身替代（不启动真实 Agent Loop），聚焦验证：
 * 深度上限、工具白名单交集、空交集拒绝、以及子代理异常转成结构化结果而非抛出。</p>
 */
class SubagentServiceTest {

    private static final Path WORKSPACE = Path.of("target/subagent-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final SubagentSessionFactory factory = new SubagentSessionFactory(ids);
    private final StubExecution execution = new StubExecution();
    private final SubagentService service = new SubagentService(execution,
            SubagentPolicy.withMaxDepth(2));

    @Test
    void rejectsWhenDepthLimitReached() {
        ChatSession deep = childSession(2);

        SubagentResult result = service.delegate(deep, request(), tools());

        assertThat(result.isError()).isTrue();
        assertThat(result.stopReason()).isEqualTo(RunStopReason.ERROR);
        assertThat(result.summary()).contains("深度已达上限");
        assertThat(execution.calls).isZero();
    }

    @Test
    void rejectsWhenWhitelistHasNoIntersection() {
        ChatSession parent = childSession(0);
        SubagentRequest narrow = new SubagentRequest(SubagentMode.SPAWN, "任务", null,
                List.of("write", "bash"), null);

        SubagentResult result = service.delegate(parent, narrow, tools());

        assertThat(result.isError()).isTrue();
        assertThat(result.summary()).contains("没有交集");
        assertThat(execution.calls).isZero();
    }

    @Test
    void delegatesWithNarrowedToolset() {
        ChatSession parent = childSession(0);
        SubagentRequest narrow = new SubagentRequest(SubagentMode.SPAWN, "分析代码", "审查者",
                List.of("read"), 60);

        SubagentResult result = service.delegate(parent, narrow, tools());

        assertThat(result.stopReason()).isEqualTo(RunStopReason.STOP);
        assertThat(execution.calls).isEqualTo(1);
        assertThat(execution.lastPrompt).contains("角色：审查者")
                .contains("分析代码")
                .contains("[read]")
                .doesNotContain("grep");
        assertThat(execution.lastChild.identity().origin())
                .isEqualTo(com.piagent.domain.session.model.Origin.SUBAGENT);
        assertThat(execution.lastChild.identity().depth()).isEqualTo(1);
    }

    @Test
    void convertsExecutionFailureIntoResult() {
        ChatSession parent = childSession(0);
        execution.failWith = new IllegalStateException("模型不可用");

        SubagentResult result = service.delegate(parent, request(), tools());

        assertThat(result.isError()).isTrue();
        assertThat(result.summary()).contains("子代理执行失败").contains("模型不可用");
    }

    @Test
    void promptMentionsOutputContract() {
        String prompt = service.promptOf(request(), tools());

        assertThat(prompt).contains("只输出最终结论").contains("无法完成时明确说明");
    }

    private SubagentRequest request() {
        return new SubagentRequest(SubagentMode.SPAWN, "调研一下", null, List.of(), null);
    }

    private List<ToolDescriptor> tools() {
        return List.of(tool("read"), tool("grep"));
    }

    private ToolDescriptor tool(String name) {
        return new ToolDescriptor(new ToolName(name), "测试工具 " + name, "{\"type\":\"object\"}",
                ToolRisk.LOW, ExecutionMode.PARALLEL, Duration.ofSeconds(5));
    }

    private ChatSession childSession(int depth) {
        ChatSession root = ChatSession.createRoot(SessionId.newId(ids), "根会话",
                new SessionConfig(WORKSPACE, "fake", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(root);
        ChatSession current = root;
        for (int level = 0; level < depth; level++) {
            current = ChatSession.createChild(SessionId.newId(ids), current, null,
                    com.piagent.domain.session.model.Origin.SUBAGENT, "子会话 " + level,
                    current.config());
            sessions.insert(current);
        }
        return current;
    }

    /**
     * 执行服务替身：记录调用并返回固定成功结果，可注入失败。
     */
    private final class StubExecution extends SubagentExecutionService {

        private int calls;
        private String lastPrompt;
        private ChatSession lastChild;
        private RuntimeException failWith;

        private StubExecution() {
            super(sessions, entries, new UnusedProvider(), factory);
        }

        @Override
        public ChatSession prepare(ChatSession parent, SubagentRequest request) {
            ChatSession child = super.prepare(parent, request);
            this.lastChild = child;
            return child;
        }

        @Override
        public SubagentResult run(ChatSession child, String prompt, Duration timeout) {
            if (failWith != null) {
                throw failWith;
            }
            calls++;
            this.lastPrompt = prompt;
            return new SubagentResult(child.identity().id(), RunStopReason.STOP, "子代理结论",
                    com.piagent.domain.session.model.TokenUsage.zero(), 100, 2, 1);
        }
    }

    /**
     * 不应被使用的 Agent Loop 提供者。
     */
    private static final class UnusedProvider implements ObjectProvider<AgentLoopService> {

        @Override
        public AgentLoopService getObject() {
            throw new IllegalStateException("测试中不应解析 Agent Loop");
        }

        @Override
        public AgentLoopService getObject(Object... args) {
            throw new IllegalStateException("测试中不应解析 Agent Loop");
        }

        @Override
        public AgentLoopService getIfAvailable() {
            return null;
        }

        @Override
        public AgentLoopService getIfUnique() {
            return null;
        }
    }
}
