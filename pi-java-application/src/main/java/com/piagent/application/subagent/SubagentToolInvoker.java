package com.piagent.application.subagent;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.subagent.SubagentMode;
import com.piagent.domain.subagent.SubagentRequest;
import com.piagent.domain.subagent.SubagentResult;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.domain.tool.port.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * {@code subagent} 工具的调用器。
 *
 * <p>它挡在真正的 {@link ToolInvoker}（内置 + 插件）之前，只截获 {@code subagent}，
 * 其余工具原样转发。这样做的原因：子代理需要读取父会话、复用 Agent Loop，
 * 这些都在应用层，而内置工具执行体在基础设施层，不能反向依赖。</p>
 *
 * <p>为什么同步：v0.1 的 run 模型是“一个会话一个活跃 run”，异步子代理会引入
 * 跨会话的并发与结果回填时机问题；同步执行让父模型像调用普通工具一样拿到结论，
 * 语义最简单，也最容易审计。</p>
 */
@Component
@Primary
public class SubagentToolInvoker implements ToolInvoker {

    private static final System.Logger LOG =
            System.getLogger(SubagentToolInvoker.class.getName());

    /** 参数键：启动模式。 */
    static final String ARG_MODE = "mode";

    /** 参数键：任务描述。 */
    static final String ARG_TASK = "task";

    /** 参数键：角色设定。 */
    static final String ARG_PERSONA = "persona";

    /** 参数键：工具白名单。 */
    static final String ARG_TOOLS = "tools";

    /** 参数键：超时秒数。 */
    static final String ARG_TIMEOUT = "timeoutSeconds";

    private final ToolInvoker delegate;
    private final SessionRepository sessions;
    private final ToolRegistry toolRegistry;
    private final SubagentService subagents;

    /**
     * 创建调用器。
     *
     * <p>{@code delegate} 必须是与内置/插件无关的“真实执行器”之外的那一层，
     * 这里按 Bean 名 {@code runtimeToolInvoker} 精确注入：容器里还有一个
     * {@code builtInToolInvoker}，按类型注入会产生歧义。</p>
     *
     * @param delegate 原工具调用器（内置 + 插件）
     * @param sessions 会话仓储
     * @param toolRegistry 实时工具目录
     * @param subagents 子代理用例
     */
    public SubagentToolInvoker(@Qualifier("runtimeToolInvoker") ToolInvoker delegate,
            SessionRepository sessions, ToolRegistry toolRegistry, SubagentService subagents) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.sessions = Objects.requireNonNull(sessions, "sessions 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.subagents = Objects.requireNonNull(subagents, "subagents 不能为空");
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        if (!SubagentRegistry.SUBAGENT_TOOL.equals(invocation.tool().toString())) {
            return delegate.invoke(invocation);
        }
        return runSubagent(invocation);
    }

    private ToolResult runSubagent(ToolInvocation invocation) {
        Optional<SubagentRequest> parsed = parse(invocation.arguments());
        if (parsed.isEmpty()) {
            return ToolResult.error(invocation.callId(), invocation.tool(),
                    "参数不合法：mode 必须是 SPAWN 或 FORK，task 不能为空");
        }
        Optional<ChatSession> parent = invocation.originSessionId() == null
                ? Optional.empty()
                : sessions.findById(invocation.originSessionId());
        if (parent.isEmpty()) {
            return ToolResult.error(invocation.callId(), invocation.tool(),
                    "无法确定发起子代理的会话，可能是直接调用而非经由 Agent Loop");
        }
        try {
            SubagentResult result = subagents.delegate(parent.get(), parsed.get(),
                    parentTools());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("childSessionId", result.childSessionId().toString());
            details.put("stopReason", result.stopReason().name());
            details.put("turns", result.turns());
            details.put("toolCalls", result.toolCalls());
            details.put("durationMs", result.durationMillis());
            details.put("usage", usageData(result));
            return new ToolResult(invocation.callId(), invocation.tool(), result.render(),
                    result.isError(), details);
        } catch (RuntimeException error) {
            // 子代理内部异常绝不能打断父循环：转成失败结果，让父模型自行决定下一步。
            LOG.log(System.Logger.Level.WARNING, "子代理调用失败", error);
            return ToolResult.error(invocation.callId(), invocation.tool(),
                    "子代理执行异常: " + message(error));
        }
    }

    /**
     * 父级可用工具：取当前工具目录，并剔除 subagent 自身。
     *
     * <p>剔除自身很关键：子工具集会在 {@code SubagentToolset} 中按深度再次裁剪，
     * 但这里先剔除可以保证即使配置了很深的 maxDepth，子代理也不会把
     * {@code subagent} 当成普通工具“继承”下去而绕过深度判断。</p>
     */
    private List<ToolDescriptor> parentTools() {
        return toolRegistry.descriptors().stream()
                .filter(tool -> !SubagentRegistry.SUBAGENT_TOOL.equals(tool.name().toString()))
                .toList();
    }

    private Map<String, Object> usageData(SubagentResult result) {
        return Map.of("input", result.usage().input(), "output", result.usage().output(),
                "totalTokens", result.usage().totalTokens());
    }

    /**
     * 解析工具参数。
     *
     * @param arguments 原始参数
     * @return 请求；参数不可用时为空
     */
    Optional<SubagentRequest> parse(Map<String, Object> arguments) {
        SubagentMode mode;
        try {
            String raw = String.valueOf(arguments.getOrDefault(ARG_MODE, "SPAWN"));
            mode = SubagentMode.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
        Object task = arguments.get(ARG_TASK);
        if (task == null || String.valueOf(task).isBlank()) {
            return Optional.empty();
        }
        Object persona = arguments.get(ARG_PERSONA);
        Object tools = arguments.get(ARG_TOOLS);
        Object timeout = arguments.get(ARG_TIMEOUT);
        try {
            return Optional.of(new SubagentRequest(mode, String.valueOf(task),
                    persona == null ? null : String.valueOf(persona), stringList(tools),
                    timeout == null ? null : Integer.valueOf(String.valueOf(timeout))));
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    private List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        if (raw == null) {
            return List.of();
        }
        String value = String.valueOf(raw).strip();
        return value.isEmpty() ? List.of() : List.of(value);
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
