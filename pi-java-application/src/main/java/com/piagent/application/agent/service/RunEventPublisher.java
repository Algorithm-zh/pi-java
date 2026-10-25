package com.piagent.application.agent.service;

import com.piagent.application.context.CompactionMode;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.service.ApprovalArgumentsSanitizer;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.llm.ToolCallDelta;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.tool.model.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 绑定单个 run 的事件发布器。
 *
 * <p>把运行循环里的业务值翻译为稳定的事件数据形状；事件只放 JSON 友好的
 * 标量、列表与 Map，不直接放领域对象，避免 SSE/CLI 依赖领域序列化细节。</p>
 */
final class RunEventPublisher {

    private static final int MAX_TOOL_CONTENT_IN_EVENT = 4_000;

    private final RunEventHub hub;
    private final RunId runId;

    RunEventPublisher(RunEventHub hub, RunId runId) {
        this.hub = hub;
        this.runId = runId;
    }

    void runStarted(AgentRun run, ModelProfile profile) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.id().toString());
        data.put("modelProfileId", profile.id().value());
        data.put("modelName", profile.endpoint().modelName());
        data.put("maxTurns", run.budget().maxTurns());
        data.put("startedAt", run.startedAt().toString());
        publish(RunEventType.RUN_STARTED, data);
    }

    void turnStarted(int turn) {
        publish(RunEventType.TURN_STARTED, Map.of("turn", turn));
    }

    void messageStarted() {
        publish(RunEventType.MESSAGE_STARTED, Map.of("role", "assistant"));
    }

    void textDelta(String text) {
        publish(RunEventType.MESSAGE_DELTA, Map.of("kind", "text", "text", text));
    }

    void thinkingDelta(String thinking) {
        publish(RunEventType.MESSAGE_DELTA, Map.of("kind", "thinking", "thinking", thinking));
    }

    void toolCallDelta(ToolCallDelta delta) {
        publish(RunEventType.MESSAGE_DELTA, Map.of(
                "kind", "toolCall",
                "index", delta.index(),
                "toolCallId", delta.toolCallId(),
                "toolName", delta.toolName(),
                "fragment", delta.argumentsFragment()));
    }

    void messageCompleted(SessionEntry entry, AssistantStopReason reason, TokenUsage usage) {
        publish(RunEventType.MESSAGE_COMPLETED, Map.of(
                "entryId", entry.id().toString(),
                "stopReason", reason.name(),
                "usage", usageData(usage)));
    }

    void toolStarted(ToolCallBlock call) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", call.id());
        data.put("toolName", call.name());
        data.put("arguments", ApprovalArgumentsSanitizer.redact(call.arguments()));
        publish(RunEventType.TOOL_STARTED, data);
    }

    void toolCompleted(ToolResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", result.callId().toString());
        data.put("toolName", result.tool().toString());
        data.put("isError", result.error());
        data.put("content", truncate(result.content()));
        data.put("details", result.details());
        publish(RunEventType.TOOL_COMPLETED, data);
    }

    void runCompleted(AgentRun run) {
        publish(RunEventType.RUN_COMPLETED, runData(run));
    }

    /**
     * 上下文压缩开始。
     *
     * @param mode 触发方式
     * @param tokensBefore 压缩前估算
     * @param availableTokens 可用输入额度
     * @param reason 触发原因
     */
    void compactionStarted(CompactionMode mode, int tokensBefore, int availableTokens,
            String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode.name());
        data.put("tokensBefore", tokensBefore);
        data.put("availableTokens", availableTokens);
        data.put("reason", reason == null ? "" : reason);
        publish(RunEventType.COMPACTION_STARTED, data);
    }

    /**
     * 上下文压缩完成或跳过。
     *
     * @param mode 触发方式
     * @param tokensBefore 压缩前估算
     * @param tokensAfter 压缩后估算
     * @param prunedOnly 是否降级为“只剪枝”（或失败回退）
     * @param note 跳过原因或失败原因；成功时为 null
     */
    void compactionCompleted(CompactionMode mode, int tokensBefore, int tokensAfter,
            boolean prunedOnly, String note) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode.name());
        data.put("tokensBefore", tokensBefore);
        data.put("tokensAfter", tokensAfter);
        data.put("savedTokens", Math.max(0, tokensBefore - tokensAfter));
        data.put("prunedOnly", prunedOnly);
        if (note != null && !note.isBlank()) {
            data.put("note", note);
        }
        publish(RunEventType.COMPACTION_COMPLETED, data);
    }

    /**
     * 一轮工具执行后工作区发生变化。
     *
     * @param payload 改动载荷
     */
    void workspaceChanged(GitChangeSetPayload payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("turn", payload.turn());
        data.put("files", payload.files().stream().map(FileChange::path).toList());
        data.put("additions", payload.additions());
        data.put("deletions", payload.deletions());
        data.put("truncated", payload.truncated());
        data.put("suggestedCommit", payload.suggestedCommit());
        publish(RunEventType.WORKSPACE_CHANGED, data);
    }

    void runFailed(AgentRun run) {
        publish(RunEventType.RUN_FAILED, runData(run));
    }

    private void publish(RunEventType type, Map<String, Object> data) {
        hub.publish(runId, type, data);
    }

    private Map<String, Object> runData(AgentRun run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.id().toString());
        data.put("status", run.status().name());
        data.put("stopReason", run.stopReason().name());
        data.put("turns", run.turns());
        data.put("toolCalls", run.toolCalls());
        data.put("usage", usageData(run.usage()));
        data.put("durationMs", run.durationMillis(run.finishedAt()));
        if (run.errorMessage() != null) {
            data.put("errorMessage", run.errorMessage());
        }
        return data;
    }

    private Map<String, Object> usageData(TokenUsage usage) {
        return Map.of(
                "input", usage.input(),
                "output", usage.output(),
                "cacheRead", usage.cacheRead(),
                "cacheWrite", usage.cacheWrite(),
                "totalTokens", usage.totalTokens());
    }

    private String truncate(String content) {
        if (content.length() <= MAX_TOOL_CONTENT_IN_EVENT) {
            return content;
        }
        return content.substring(0, MAX_TOOL_CONTENT_IN_EVENT) + "\n…（事件内容已截断）";
    }
}
