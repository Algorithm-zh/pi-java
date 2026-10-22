package com.piagent.application.agent.service;

import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.testsupport.DirectExecutorService;
import com.piagent.application.testsupport.InMemoryModelProfileRepository;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.InMemoryToolPolicyRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.governance.service.ToolAccessPolicy;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.service.ModelSelectionPolicy;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.service.SessionContextProjector;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.domain.tool.port.ToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Agent Loop 应用层测试的装配夹具。
 *
 * <p>用真实 Loop、运行时、追加器与协调器，替换 LLM、工具与持久化端口，
 * 从而在不启动 Spring 的情况下验证状态机、预算、取消与工具配对。</p>
 */
final class AgentLoopFixture {

    static final String MODEL_ID = "loop-fake";
    static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    static final Path WORKSPACE = Path.of("target/agent-loop-unit").toAbsolutePath().normalize();

    final InMemorySessionRepository sessions = new InMemorySessionRepository();
    final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    final InMemoryModelProfileRepository models = new InMemoryModelProfileRepository();
    final InMemoryToolPolicyRepository policies = new InMemoryToolPolicyRepository();
    final ActiveRunRegistry registry = new ActiveRunRegistry();
    final RunEventHub hub = new RunEventHub(Clock.fixed(NOW, ZoneOffset.UTC));
    final ScriptedLlmGateway gateway = new ScriptedLlmGateway();
    final StubToolRegistry tools = new StubToolRegistry();
    final RecordingToolInvoker invoker = new RecordingToolInvoker();
    final StubApprovalGate approvals = new StubApprovalGate();
    final SessionEntryAppender appender;
    final RunRuntime runtime;
    final TurnPipeline pipeline;
    final RunDispatcher dispatcher;
    final AgentLoopService loop;
    final ModelProfile profile;

    private final SequentialIdGenerator ids = new SequentialIdGenerator();

    AgentLoopFixture() {
        this(2);
    }

    AgentLoopFixture(int maxConcurrentRuns) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DirectExecutorService executor = new DirectExecutorService();
        appender = new SessionEntryAppender(sessions, entries, ids, clock);
        runtime = new RunRuntime(registry, hub, appender, tools, ids, clock);
        profile = ModelProfile.create(new ModelId(MODEL_ID), "Loop Fake",
                ModelEndpoint.fake(MODEL_ID));
        models.insert(profile);
        ToolCallPreflight preflight = new ToolCallPreflight(policies, new ToolAccessPolicy());
        ToolCallCoordinator coordinator = new ToolCallCoordinator(invoker, appender, approvals,
                preflight, executor);
        pipeline = new TurnPipeline(entries, new SessionContextProjector(),
                new SystemPromptBuilder(), new TurnExecutor(gateway, registry), coordinator);
        dispatcher = new RunDispatcher(executor, maxConcurrentRuns);
        loop = new AgentLoopService(sessions,
                new ActiveModelResolver(models, new ModelSelectionPolicy()),
                runtime, pipeline, dispatcher);
    }

    ChatSession createSession() {
        return createSession(MODEL_ID);
    }

    ChatSession createSession(String modelId) {
        ChatSession session = ChatSession.createRoot(SessionId.newId(ids), "Loop 测试",
                new SessionConfig(WORKSPACE, modelId, ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(session);
        return session;
    }

    AgentRun run(SessionId sessionId, String text) {
        RunHandle handle = loop.start(new SendMessageCommand(sessionId, text));
        return loop.awaitTerminal(handle.runId(), Duration.ofSeconds(2));
    }

    ChatSession session(SessionId sessionId) {
        return sessions.findById(sessionId).orElseThrow();
    }

    List<SessionEntry> branch(SessionId sessionId) {
        ChatSession session = session(sessionId);
        return entries.loadBranch(sessionId, session.leafEntryId());
    }

    List<RunEvent> events(RunId runId) {
        List<RunEvent> collected = new ArrayList<>();
        hub.subscribe(runId, 0, collected::add).close();
        return collected;
    }

    /**
     * 可配置的内置工具目录替身。
     */
    static final class StubToolRegistry implements ToolRegistry {

        private final Map<ToolName, ToolDescriptor> definitions = new LinkedHashMap<>();

        StubToolRegistry add(String name, ToolRisk risk, ExecutionMode mode) {
            ToolName tool = new ToolName(name);
            definitions.put(tool, new ToolDescriptor(tool, "测试工具 " + name,
                    "{\"type\":\"object\"}", risk, mode, Duration.ofSeconds(5)));
            return this;
        }

        void remove(String name) {
            definitions.remove(new ToolName(name));
        }

        @Override
        public List<ToolDescriptor> descriptors() {
            return List.copyOf(definitions.values());
        }

        @Override
        public Optional<ToolDescriptor> find(ToolName name) {
            return Optional.ofNullable(definitions.get(name));
        }

        @Override
        public ToolDescriptor require(ToolName name) {
            return find(name).orElseThrow(() -> new DomainException(
                    DomainErrorCode.TOOL_NOT_FOUND, "工具不存在: " + name));
        }
    }

    /**
     * 按队列返回审批终态的替身，默认拒绝。
     */
    static final class StubApprovalGate implements ApprovalGate {

        private final Deque<ApprovalStatus> outcomes = new ArrayDeque<>();
        private final List<ApprovalContext> contexts = new ArrayList<>();

        StubApprovalGate enqueue(ApprovalStatus status) {
            outcomes.addLast(status);
            return this;
        }

        List<ApprovalContext> contexts() {
            return List.copyOf(contexts);
        }

        @Override
        public ApprovalRequest await(ApprovalContext context, BooleanSupplier cancelled) {
            contexts.add(context);
            ApprovalStatus status = outcomes.isEmpty()
                    ? ApprovalStatus.DENIED : outcomes.removeFirst();
            ApprovalRequest pending = ApprovalRequest.pending(new ApprovalId(Ulid.generate(NOW)),
                    context, NOW, Duration.ofMinutes(5));
            return switch (status) {
                case APPROVED -> pending.approve("user", "测试批准", NOW.plusSeconds(1));
                case DENIED -> pending.deny("user", "测试拒绝", NOW.plusSeconds(1));
                case EXPIRED -> pending.expire(NOW.plusSeconds(1));
                case CANCELLED -> pending.cancel("测试取消", NOW.plusSeconds(1));
                default -> throw new IllegalStateException("不支持的测试审批状态: " + status);
            };
        }
    }

    /**
     * 记录调用参数并返回成功结果的工具执行器替身。
     */
    static final class RecordingToolInvoker implements ToolInvoker {

        private final List<ToolInvocation> invocations = new ArrayList<>();

        List<ToolInvocation> invocations() {
            return List.copyOf(invocations);
        }

        @Override
        public ToolResult invoke(ToolInvocation invocation) {
            invocations.add(invocation);
            return ToolResult.success(invocation.callId(), invocation.tool(),
                    "tool:" + invocation.tool(), Map.of());
        }
    }
}
