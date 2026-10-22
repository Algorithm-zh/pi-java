package com.piagent.application.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.subagent.SubagentMode;
import com.piagent.domain.subagent.SubagentRequest;
import com.piagent.domain.subagent.SubagentResult;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.domain.tool.port.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code subagent} 工具调用器测试。
 *
 * <p>验证三件事：只截获 subagent、参数解析与容错、父会话缺失时给出可读错误。</p>
 */
class SubagentToolInvokerTest {

    private static final Path WORKSPACE = Path.of("target/subagent-tool").toAbsolutePath();
    private static final String SUBAGENT = SubagentRegistry.SUBAGENT_TOOL;

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private ToolInvoker delegate;
    private SubagentService subagents;
    private SubagentToolInvoker invoker;
    private ChatSession parent;

    @BeforeEach
    void setUp() {
        delegate = mock(ToolInvoker.class);
        subagents = mock(SubagentService.class);
        ToolRegistry registry = new FixedRegistry(List.of(tool("read"), tool(SUBAGENT)));
        invoker = new SubagentToolInvoker(delegate, sessions, registry, subagents);
        parent = ChatSession.createRoot(SessionId.newId(ids), "父会话",
                new SessionConfig(WORKSPACE, "fake", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(parent);
    }

    /**
     * 固定工具目录的测试替身。
     */
    private record FixedRegistry(List<ToolDescriptor> tools) implements ToolRegistry {

        @Override
        public List<ToolDescriptor> descriptors() {
            return tools;
        }

        @Override
        public Optional<ToolDescriptor> find(ToolName name) {
            return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
        }

        @Override
        public ToolDescriptor require(ToolName name) {
            return find(name).orElseThrow(() -> new IllegalStateException("工具不存在: " + name));
        }
    }

    @Test
    void forwardsNonSubagentToolsToDelegate() {
        ToolResult expected = ToolResult.success(callId(), new ToolName("read"), "内容", Map.of());
        when(delegate.invoke(any())).thenReturn(expected);

        ToolResult result = invoker.invoke(invocation("read", Map.of("path", "a.txt")));

        assertThat(result).isEqualTo(expected);
        verify(subagents, never()).delegate(any(), any(), any());
    }

    @Test
    void delegatesSubagentAndReturnsStructuredDetails() {
        when(subagents.delegate(any(), any(), any())).thenReturn(new SubagentResult(
                parent.identity().id(), RunStopReason.STOP, "结论", tokenUsage(), 500, 3, 2));

        ToolResult result = invoker.invoke(invocation(SUBAGENT, Map.of(
                "mode", "FORK", "task", "检查代码", "tools", List.of("read"), "timeoutSeconds", 60)));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("结论", "子会话：");
        assertThat(result.details()).containsEntry("stopReason", "STOP")
                .containsEntry("turns", 3)
                .containsEntry("toolCalls", 2);

        SubagentRequest captured = captureRequest();
        assertThat(captured.mode()).isEqualTo(SubagentMode.FORK);
        assertThat(captured.allowedTools()).containsExactly("read");
        assertThat(captured.timeoutSeconds()).isEqualTo(60);
        verify(delegate, never()).invoke(any());
    }

    @Test
    void rejectsInvalidArguments() {
        ToolResult result = invoker.invoke(invocation(SUBAGENT, Map.of("mode", "WRONG",
                "task", "任务")));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("参数不合法");
    }

    @Test
    void rejectsWhenOriginSessionUnknown() {
        ToolInvocation withoutOrigin = new ToolInvocation(callId(), new ToolName(SUBAGENT),
                Map.of("task", "任务"), sandbox());

        ToolResult result = invoker.invoke(withoutOrigin);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("无法确定发起子代理的会话");
    }

    @Test
    void turnsUnexpectedFailureIntoErrorResult() {
        when(subagents.delegate(any(), any(), any()))
                .thenThrow(new IllegalStateException("意外错误"));

        ToolResult result = invoker.invoke(invocation(SUBAGENT, Map.of("task", "任务")));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("子代理执行异常", "意外错误");
    }

    private SubagentRequest captureRequest() {
        org.mockito.ArgumentCaptor<SubagentRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SubagentRequest.class);
        verify(subagents).delegate(any(), captor.capture(), any());
        return captor.getValue();
    }

    private com.piagent.domain.session.model.TokenUsage tokenUsage() {
        return new com.piagent.domain.session.model.TokenUsage(10, 5, 0, 0, 15);
    }

    private ToolCallId callId() {
        return new ToolCallId("call-1");
    }

    private com.piagent.domain.governance.model.SandboxPolicy sandbox() {
        return new com.piagent.domain.governance.model.SandboxPolicy(WORKSPACE,
                List.of(WORKSPACE), false);
    }

    private ToolInvocation invocation(String tool, Map<String, Object> arguments) {
        return new ToolInvocation(callId(), new ToolName(tool), arguments, sandbox(),
                parent.identity().id());
    }

    private ToolDescriptor tool(String name) {
        return new ToolDescriptor(new ToolName(name), "测试工具 " + name, "{\"type\":\"object\"}",
                ToolRisk.LOW, com.piagent.domain.tool.model.ExecutionMode.PARALLEL,
                java.time.Duration.ofSeconds(5));
    }
}
