package com.piagent.trigger.cli;

import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.CustomMessagePayload;
import com.piagent.domain.session.model.payload.CustomPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.LabelPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.ModelChangePayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.SessionInfoPayload;
import java.util.stream.Collectors;

/**
 * 会话 entry 的单行投影，供 {@code sessions show} 与 {@code /tree} 复用。
 *
 * <p>只做展示，不改变任何状态；未知载荷回退为 entry 类型名，保证新旧数据都能显示。</p>
 */
public final class CliProjection {

    private CliProjection() {
    }

    /**
     * 生成 entry 的一行摘要。
     *
     * @param entry 会话 entry
     * @return 单行摘要
     */
    public static String summary(SessionEntry entry) {
        return switch (entry.type()) {
            case MESSAGE -> messageSummary(entry.payload());
            case CUSTOM_MESSAGE -> customMessageSummary(entry.payload());
            case COMPACTION -> compactionSummary(entry.payload());
            case BRANCH_SUMMARY -> branchSummary(entry.payload());
            case MODEL_CHANGE -> modelChangeSummary(entry.payload());
            case APPROVAL -> approvalSummary(entry.payload());
            case RUN_SUMMARY -> runSummary(entry.payload());
            case GIT_CHECKPOINT -> checkpointSummary(entry.payload());
            case GIT_CHANGES -> changesSummary(entry.payload());
            case SESSION_INFO -> entry.payload() instanceof SessionInfoPayload info
                    ? "会话信息: " + info.name() : "会话信息";
            case LABEL -> labelSummary(entry.payload());
            case CUSTOM -> entry.payload() instanceof CustomPayload custom
                    ? "扩展: " + custom.customType() : "扩展";
        };
    }

    private static String messageSummary(Object payload) {
        if (!(payload instanceof MessagePayload messagePayload)) {
            return "消息";
        }
        return switch (messagePayload.message()) {
            case ToolResultMessage tool -> toolSummary(tool);
            case AssistantMessage assistant -> assistantSummary(assistant);
            case AgentMessage message -> roleSummary(message);
        };
    }

    private static String customMessageSummary(Object payload) {
        if (payload instanceof CustomMessagePayload custom) {
            return "扩展消息 " + custom.customType() + ": "
                    + CliText.oneLine(CliText.textOf(custom.content()), 120);
        }
        return "扩展消息";
    }

    private static String compactionSummary(Object payload) {
        if (payload instanceof CompactionPayload compaction) {
            return "压缩摘要 (" + compaction.tokensBefore() + " token): "
                    + CliText.oneLine(compaction.summary(), 120);
        }
        return "压缩摘要";
    }

    private static String branchSummary(Object payload) {
        if (payload instanceof BranchSummaryPayload branch) {
            return "分支摘要: " + CliText.oneLine(branch.summary(), 120);
        }
        return "分支摘要";
    }

    private static String modelChangeSummary(Object payload) {
        if (payload instanceof ModelChangePayload change) {
            return "模型切换: " + change.modelProfileId() + " / " + change.thinkingLevel();
        }
        return "模型切换";
    }

    private static String approvalSummary(Object payload) {
        if (payload instanceof ApprovalPayload approval) {
            String reason = approval.reason() == null || approval.reason().isBlank()
                    ? "" : " · " + approval.reason();
            return "审批 " + approval.toolName() + ": " + approval.decision() + reason;
        }
        return "审批";
    }

    private static String runSummary(Object payload) {
        if (payload instanceof RunSummaryPayload summary) {
            return "运行摘要: " + summary.stopReason() + " · " + summary.turns() + " 轮 · "
                    + summary.toolCalls() + " 工具 · " + summary.durationMs() + "ms";
        }
        return "运行摘要";
    }

    private static String checkpointSummary(Object payload) {
        if (payload instanceof GitCheckpointPayload checkpoint) {
            String branch = checkpoint.branch() == null ? "游离 HEAD" : checkpoint.branch();
            return "检查点: " + branch + " @ " + shortSha(checkpoint.baseTreeSha());
        }
        return "检查点";
    }

    private static String changesSummary(Object payload) {
        if (payload instanceof GitChangeSetPayload changes) {
            String commit = changes.suggestedCommit().isBlank()
                    ? "" : " · 建议提交: " + changes.suggestedCommit();
            return "本轮改动: " + changes.files().size() + " 个文件 +"
                    + changes.additions() + " -" + changes.deletions() + commit;
        }
        return "工作区改动";
    }

    private static String shortSha(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static String labelSummary(Object payload) {
        if (payload instanceof LabelPayload label) {
            return label.label() == null ? "书签清除" : "书签: " + label.label();
        }
        return "书签";
    }

    private static String toolSummary(ToolResultMessage tool) {
        String status = tool.isError() ? "失败" : "完成";
        return "工具 " + tool.toolName() + " " + status + ": "
                + CliText.oneLine(CliText.textOf(tool.content()), 120);
    }

    private static String assistantSummary(AssistantMessage assistant) {
        String text = CliText.textOf(assistant.content());
        if (!text.isBlank()) {
            return "助手: " + CliText.oneLine(text, 120);
        }
        String tools = assistant.content().stream()
                .filter(ToolCallBlock.class::isInstance)
                .map(ToolCallBlock.class::cast)
                .map(ToolCallBlock::name)
                .collect(Collectors.joining(", "));
        return tools.isBlank() ? "助手" : "助手调用工具: " + tools;
    }

    private static String roleSummary(AgentMessage message) {
        if (message instanceof UserMessage user) {
            return "用户: " + CliText.oneLine(CliText.textOf(user.content()), 120);
        }
        return "消息";
    }
}
