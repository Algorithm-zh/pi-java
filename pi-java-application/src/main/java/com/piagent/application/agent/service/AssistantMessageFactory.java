package com.piagent.application.agent.service;

import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.Usage;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ThinkingBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一次流式调用结果转换为可持久化的 assistant 消息。
 *
 * <p>正常结束时直接复用完整响应内容；取消时没有完整响应，只保留已流出的文本与
 * 思考片段，并标记为 {@link AssistantStopReason#ABORTED}，确保中断现场可回放。</p>
 */
final class AssistantMessageFactory {

    private AssistantMessageFactory() {
    }

    /**
     * 构造 assistant 消息。
     *
     * @param profile 模型档案
     * @param result 流式结果
     * @param now 创建时间
     * @return 助手消息
     */
    static AssistantMessage from(ModelProfile profile, TurnResult result, Instant now) {
        LlmResponse response = result.response();
        if (response == null) {
            return new AssistantMessage(partialContent(result.streamedText(), result.streamedThinking()),
                    profile.ref().provider().name(), profile.ref().modelName(), TokenUsage.zero(),
                    AssistantStopReason.ABORTED, null, now);
        }
        return new AssistantMessage(response.content(), profile.ref().provider().name(),
                profile.ref().modelName(), toTokenUsage(response.usage()), response.stopReason(),
                response.errorMessage(), now);
    }

    private static List<ContentBlock> partialContent(String text, String thinking) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (thinking != null && !thinking.isBlank()) {
            blocks.add(new ThinkingBlock(thinking, null));
        }
        if (text != null && !text.isBlank()) {
            blocks.add(new TextBlock(text));
        }
        return List.copyOf(blocks);
    }

    private static TokenUsage toTokenUsage(Usage usage) {
        return new TokenUsage(usage.input(), usage.output(), usage.cacheRead(),
                usage.cacheWrite(), usage.totalTokens());
    }
}
