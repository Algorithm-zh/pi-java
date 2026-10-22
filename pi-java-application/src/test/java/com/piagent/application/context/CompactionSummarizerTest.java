package com.piagent.application.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.ScriptedLlmGateway;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.context.CompactionPromptBuilder;
import com.piagent.domain.context.SummaryDraft;
import com.piagent.domain.context.SummarySection;
import com.piagent.domain.context.TokenEstimator;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 摘要生成与解析测试。
 *
 * <p>覆盖：标题解析、文件清单抽取、无标题兜底、空输出报错、网关异常透传、
 * 以及发给模型的请求形状（无工具、单条 user 消息）。</p>
 */
class CompactionSummarizerTest {

    private static final ModelProfile PROFILE = ModelProfile.create(new ModelId("fake"), "Fake",
            ModelEndpoint.fake("fake"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),
            ZoneOffset.UTC);

    private final ScriptedLlmGateway gateway = new ScriptedLlmGateway();
    private final CompactionSummarizer summarizer = new CompactionSummarizer(gateway,
            new CompactionPromptBuilder(), new TokenEstimator(), CLOCK);

    @Test
    void parsesSectionsAndFileLists() {
        SummaryDraft draft = summarizer.parse("""
                ## 目标
                完成压缩功能
                ## 约束
                - 不能引入 YAML 库
                ## 关键上下文
                读取文件：README.md, docs/01.md
                修改文件：pom.xml
                其余说明
                """);

        assertThat(draft.section(SummarySection.GOAL)).isEqualTo("完成压缩功能");
        assertThat(draft.section(SummarySection.CONSTRAINTS)).contains("不能引入 YAML 库");
        assertThat(draft.readFiles()).containsExactly("README.md", "docs/01.md");
        assertThat(draft.modifiedFiles()).containsExactly("pom.xml");
        assertThat(draft.section(SummarySection.CRITICAL_CONTEXT)).isEqualTo("其余说明");
    }

    @Test
    void acceptsBoldAndColonHeadings() {
        SummaryDraft draft = summarizer.parse("""
                **目标**: 修复登录
                **下一步**:
                1. 跑测试
                """);

        assertThat(draft.section(SummarySection.GOAL)).isEqualTo("修复登录");
        assertThat(draft.section(SummarySection.NEXT_STEPS)).contains("跑测试");
    }

    @Test
    void fallsBackToGoalWhenNoHeadingRecognized() {
        SummaryDraft draft = summarizer.parse("模型直接写了一段没有标题的摘要。");

        assertThat(draft.section(SummarySection.GOAL)).contains("没有标题的摘要");
    }

    @Test
    void generateSendsSingleUserMessageWithoutTools() {
        gateway.enqueue(ScriptedLlmGateway.text("## 目标\n完成"));

        SummaryDraft draft = summarizer.summarize(PROFILE, entries(), List.of(), null);

        assertThat(draft.section(SummarySection.GOAL)).isEqualTo("完成");
        var request = gateway.requests().get(0);
        assertThat(request.tools()).isEmpty();
        assertThat(request.messages()).hasSize(1);
        assertThat(request.systemPrompt()).contains("上下文压缩器");
    }

    @Test
    void rejectsEmptyOutput() {
        gateway.enqueue(ScriptedLlmGateway.text(""));

        assertThatThrownBy(() -> summarizer.summarize(PROFILE, entries(), List.of(), null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("没有返回摘要内容");
    }

    @Test
    void propagatesGatewayFailures() {
        gateway.enqueue(ScriptedLlmGateway.failure(
                new LlmGatewayException(LlmErrorCode.SERVER_ERROR, "上游 500")));

        assertThatThrownBy(() -> summarizer.summarize(PROFILE, entries(), List.of(), null))
                .isInstanceOf(LlmGatewayException.class);
    }

    @Test
    void estimatesTokensOfSummary() {
        assertThat(summarizer.estimateTokens("你好世界")).isEqualTo(4);
        assertThat(summarizer.estimateTokens("")).isZero();
    }

    private List<SessionEntry> entries() {
        SessionEntry user = SessionEntryFactory.message(SessionEntryFactory.sessionId(1), 1, null,
                SessionEntryFactory.user("实现压缩"), 10);
        SessionEntry assistant = SessionEntryFactory.message(SessionEntryFactory.sessionId(1), 2,
                user.id(), SessionEntryFactory.assistant("好的"), 10);
        return List.of(user, assistant);
    }
}
