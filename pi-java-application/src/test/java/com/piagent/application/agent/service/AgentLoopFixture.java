package com.piagent.application.agent.service;

import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.context.CompactionComponents;
import com.piagent.application.context.CompactionSummarizer;
import com.piagent.application.context.ContextCompactionService;
import com.piagent.application.context.ContextMeasurementFactory;
import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.testsupport.DirectExecutorService;import com.piagent.application.testsupport.InMemoryModelProfileRepository;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.InMemorySkillSource;
import com.piagent.application.testsupport.InMemoryToolPolicyRepository;
import com.piagent.application.testsupport.ScriptedLlmGateway;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.StubWorkspacePromptSource;
import com.piagent.application.verify.VerificationOptions;
import com.piagent.application.verify.VerificationService;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.context.CompactionPlanner;
import com.piagent.domain.context.CompactionPromptBuilder;
import com.piagent.domain.context.CompactionSummaryFormatter;
import com.piagent.domain.context.CompactionTriggerPolicy;
import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.context.SkillPromptFormatter;
import com.piagent.domain.context.TokenEstimator;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
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
import com.piagent.domain.verify.model.VerificationOutcome;
import com.piagent.domain.verify.port.VerificationRunner;
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
    final ContextCompactionState compactionState;
    final InMemorySkillSource skillSource;
    /** 两次压缩之间的最小新增 token；测试里用小值便于触发。 */
    private static final int MIN_GROWTH_TOKENS = 0;
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
        this(defaultProfile(), maxConcurrentRuns);
    }

    /**
     * 用自定义 Git 端口装配，便于验证快照与改动事件。
     *
     * @param workspaceGit 工作区 Git 端口
     */
    AgentLoopFixture(WorkspaceGit workspaceGit) {
        this(defaultProfile(), 2, workspaceGit);
    }

    /**
     * 用指定模型档案装配，便于测试自定义上下文窗口。
     *
     * @param profile 模型档案
     */
    AgentLoopFixture(ModelProfile profile) {
        this(profile, 2);
    }

    private static ModelProfile defaultProfile() {
        return ModelProfile.create(new ModelId(MODEL_ID), "Loop Fake",
                ModelEndpoint.fake(MODEL_ID));
    }

    private AgentLoopFixture(ModelProfile profile, int maxConcurrentRuns) {
        this(profile, maxConcurrentRuns, new NoGit());
    }

    private AgentLoopFixture(ModelProfile profile, int maxConcurrentRuns, WorkspaceGit workspaceGit) {
        this(profile, maxConcurrentRuns, workspaceGit, passingRunner(),
                VerificationOptions.disabled());
    }

    /**
     * 用自定义验证配置装配，便于验证失败回写与重试上限。
     *
     * @param runner 验证执行器
     * @param options 验证配置
     */
    AgentLoopFixture(VerificationRunner runner, VerificationOptions options) {
        this(defaultProfile(), 2, new NoGit(), runner, options);
    }

    private AgentLoopFixture(ModelProfile profile, int maxConcurrentRuns,
            WorkspaceGit workspaceGit, VerificationRunner runner, VerificationOptions options) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DirectExecutorService executor = new DirectExecutorService();
        appender = new SessionEntryAppender(sessions, entries, ids, clock);
        compactionState = new ContextCompactionState();
        runtime = new RunRuntime(new RunRuntimeDependencies(registry, hub, appender, tools,
                compactionState, sessions, ids, clock));
        this.profile = profile;
        models.insert(profile);
        ToolCallPreflight preflight = new ToolCallPreflight(policies, new ToolAccessPolicy());
        ToolCallCoordinator coordinator = new ToolCallCoordinator(invoker, appender, approvals,
                preflight, executor);
        skillSource = new InMemorySkillSource();
        SystemPromptBuilder promptBuilder = new SystemPromptBuilder(skillSource,
                new StubWorkspacePromptSource(), new SkillPromptFormatter());
        pipeline = new TurnPipeline(entries, new SessionContextProjector(), promptBuilder,
                new TurnExecutor(gateway, registry), coordinator, skillSource);
        TokenEstimator estimator = new TokenEstimator();
        CompactionComponents components = new CompactionComponents(
                new EntryTokenCounter(estimator), estimator,
                new CompactionTriggerPolicy(MIN_GROWTH_TOKENS), new CompactionPlanner(),
                new CompactionSummarizer(gateway, new CompactionPromptBuilder(), estimator, clock),
                new CompactionSummaryFormatter());
        ContextCompactionService compaction = new ContextCompactionService(components,
                appender::appendCompaction);
        RunCompactionStep compactionStep = new RunCompactionStep(entries, compaction,
                compactionState, new ContextMeasurementFactory(new SessionContextProjector(),
                        compaction));
        dispatcher = new RunDispatcher(executor, maxConcurrentRuns);
        GitCheckpointService git = new GitCheckpointService(workspaceGit, sessions, entries, appender);
        VerificationService verification = new VerificationService(runner, appender, runtime,
                new RunAuxEvents(runtime, hub), options, clock);
        loop = new AgentLoopService(new AgentLoopDependencies(
                new ActiveModelResolver(models, new ModelSelectionPolicy()),
                runtime, pipeline, dispatcher, compactionStep, git, verification));
    }

    private static VerificationRunner passingRunner() {
        return (command, workspace, timeout, maxChars) ->
                new VerificationOutcome(command, 0, false, false, 0, "");
    }

    ChatSession createSession() {
        return createSession(profile.id().value());
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
     * 测试里关闭 Git：快照为空，run 不写检查点 entry。
     */
    static final class NoGit implements WorkspaceGit {

        @Override
        public Optional<GitSnapshot> snapshot(Path workspace) {
            return Optional.empty();
        }

        @Override
        public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
            throw new UnsupportedOperationException("测试未启用 Git");
        }

        @Override
        public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
            throw new UnsupportedOperationException("测试未启用 Git");
        }
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
