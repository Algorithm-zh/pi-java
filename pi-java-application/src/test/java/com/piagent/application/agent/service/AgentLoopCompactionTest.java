package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.ScriptedLlmGateway;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.payload.CompactionPayload;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * run 内上下文压缩与技能指令的集成测试（应用层，不启动 Spring）。
 *
 * <p>为了让压缩在真实规模下触发，这里使用 32k 窗口的模型档案：默认保留额度会收敛到
 * 可用额度的 80% 左右，几段长消息即可超出。覆盖：自动压缩、溢出后压缩重试、
 * 压缩失败不阻断/阻断的两种收尾、{@code /skill:} 展开与未知技能回退。</p>
 */
class AgentLoopCompactionTest {

    private static final int SMALL_WINDOW = 32_768;

    @Test
    void compactsAfterContextOverflowAndRetriesOnce() {
        AgentLoopFixture fixture = smallWindowFixture();
        ChatSession session = fixture.createSession();
        seedHistory(fixture, session);
        // 序号：1 自动压缩摘要；2 溢出失败；3 强制压缩摘要；4 重试成功。
        fixture.gateway.enqueue(compactSummary());
        fixture.gateway.enqueue(overflow());
        fixture.gateway.enqueue(compactSummary());
        fixture.gateway.enqueue(ScriptedLlmGateway.text("压缩后继续"));

        AgentRun run = fixture.run(session.identity().id(), "继续");

        assertThat(run.status()).isEqualTo(com.piagent.domain.agent.RunStatus.COMPLETED);
        assertThat(hasCompaction(fixture, session)).isTrue();
        assertThat(fixture.events(run.id())).extracting(event -> event.type())
                .contains(RunEventType.COMPACTION_STARTED, RunEventType.COMPACTION_COMPLETED);
    }

    @Test
    void failsRunWhenOverflowPersistsAfterCompaction() {
        AgentLoopFixture fixture = smallWindowFixture();
        ChatSession session = fixture.createSession();
        seedHistory(fixture, session);
        fixture.gateway.enqueue(compactSummary());
        fixture.gateway.enqueue(overflow());
        fixture.gateway.enqueue(compactSummary());
        fixture.gateway.enqueue(overflow());

        AgentRun run = fixture.run(session.identity().id(), "继续");

        assertThat(run.status()).isEqualTo(com.piagent.domain.agent.RunStatus.FAILED);
        assertThat(run.errorMessage()).contains("压缩后仍无法调用模型");
    }

    @Test
    void failsRunWhenCompactionProducesNothing() {
        AgentLoopFixture fixture = smallWindowFixture();
        ChatSession session = fixture.createSession();
        seedHistory(fixture, session);
        long historyCompactions = countCompactions(fixture, session);
        // 自动压缩拿到空摘要（被吞掉），随后溢出，强制压缩仍然拿不到摘要。
        fixture.gateway.enqueue(ScriptedLlmGateway.text("   "));
        fixture.gateway.enqueue(overflow());
        fixture.gateway.enqueue(ScriptedLlmGateway.text("   "));

        AgentRun run = fixture.run(session.identity().id(), "继续");

        assertThat(run.status()).isEqualTo(com.piagent.domain.agent.RunStatus.FAILED);
        assertThat(run.errorMessage()).contains("无法压缩");
        // 本次 run 中不应新增 COMPACTION entry（历史轮次里可能已有自动压缩）。
        assertThat(fixture.branch(session.identity().id()).stream()
                .filter(entry -> entry.payload() instanceof CompactionPayload)
                .count()).isEqualTo(historyCompactions);
    }

    @Test
    void expandsSkillCommandIntoUserMessage() {
        AgentLoopFixture fixture = smallWindowFixture();
        fixture.skillSource.register(new SkillDescriptor("deploy", "发布流程",
                Path.of("skills/deploy/SKILL.md"), "test"), "# 发布\n1. 打包");
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.text("好的"));

        AgentRun run = fixture.run(session.identity().id(), "/skill:deploy 发布 1.2.0");

        assertThat(run.status()).isEqualTo(com.piagent.domain.agent.RunStatus.COMPLETED);
        SessionEntry user = fixture.branch(session.identity().id()).stream()
                .filter(entry -> entry.type() == EntryType.MESSAGE)
                .findFirst().orElseThrow();
        assertThat(user.payload().toString()).contains("1. 打包").contains("本次任务：发布 1.2.0");
        assertThat(fixture.gateway.requests().get(0).systemPrompt())
                .contains("技能 deploy 的完整说明");
    }

    @Test
    void treatsUnknownSkillAsPlainMessage() {
        AgentLoopFixture fixture = smallWindowFixture();
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.text("没找到这个技能"));

        fixture.run(session.identity().id(), "/skill:missing 你好");

        assertThat(fixture.gateway.requests().get(0).systemPrompt())
                .doesNotContain("技能 missing");
    }

    private AgentLoopFixture smallWindowFixture() {
        return new AgentLoopFixture(ModelProfile.create(new ModelId("loop-small"), "Loop Small",
                ModelEndpoint.openAiCompatible("http://127.0.0.1:1", "PI_KEY", "small",
                        SMALL_WINDOW, 8_192)));
    }

    private LlmStream compactSummary() {
        return ScriptedLlmGateway.text("## 目标\n继续任务\n## 下一步\n收尾");
    }

    private LlmStream overflow() {
        return ScriptedLlmGateway.failure(
                new LlmGatewayException(LlmErrorCode.CONTEXT_OVERFLOW, "超出窗口"));
    }

    /**
     * 写入三段长历史：每段约 7k token，合计超过 32k 窗口的可用额度。
     */
    private void seedHistory(AgentLoopFixture fixture, ChatSession session) {
        for (int index = 0; index < 3; index++) {
            fixture.gateway.enqueue(ScriptedLlmGateway.text("记下了"));
            fixture.run(session.identity().id(), "历史 " + index + " " + "内容".repeat(7_000));
        }
    }

    private boolean hasCompaction(AgentLoopFixture fixture, ChatSession session) {
        return countCompactions(fixture, session) > 0;
    }

    private long countCompactions(AgentLoopFixture fixture, ChatSession session) {
        return fixture.branch(session.identity().id()).stream()
                .filter(entry -> entry.payload() instanceof CompactionPayload)
                .count();
    }
}
