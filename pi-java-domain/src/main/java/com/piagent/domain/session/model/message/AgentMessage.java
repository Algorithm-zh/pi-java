package com.piagent.domain.session.model.message;

/**
 * 会话上下文中的消息。
 *
 * <p>前三种是持久化到 MESSAGE entry 的真实消息；后两种由投影器根据
 * COMPACTION / BRANCH_SUMMARY entry 生成，只存在于投影结果中，不会再次落库。
 */
public sealed interface AgentMessage permits
        UserMessage, AssistantMessage, ToolResultMessage,
        CompactionSummaryMessage, BranchSummaryMessage {
}
