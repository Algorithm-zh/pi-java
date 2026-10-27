package com.piagent.infrastructure.llm.fake;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.ToolCallDelta;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.ImageBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Demo 确定性脚本的单元测试。
 *
 * <p>覆盖只读指令识别、默认路径、引号清理、工具结果摘要与截断，确保
 * 零配置演示的输入输出稳定可预期。</p>
 */
class DemoTurnScriptTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final int PREVIEW_LENGTH = 1_200;

    @Test
    void readCommandProducesReadToolCall() {
        FakeTurn turn = DemoTurnScript.next(request(user("/read hello.txt")));

        assertThat(turn.response().stopReason()).isEqualTo(AssistantStopReason.TOOL_USE);
        assertThat(turn.response().content()).hasSize(1);
        assertThat(turn.response().content().getFirst())
                .isInstanceOfSatisfying(ToolCallBlock.class, call -> {
                    assertThat(call.id()).startsWith("demo-");
                    assertThat(call.name()).isEqualTo("read");
                    assertThat(call.arguments()).containsEntry("path", "hello.txt");
                });
        assertThat(turn.deltas()).hasSize(2);
        assertThat(turn.deltas()).allMatch(ToolCallDelta.class::isInstance);
    }

    @Test
    void chineseReadAliasStripsQuotesAndFallsBackToReadme() {
        FakeTurn quoted = DemoTurnScript.next(
                request(user("看看 \"src/main/App.java\"")));
        FakeTurn fallback = DemoTurnScript.next(request(user("读取文件")));

        assertThat(toolArguments(quoted)).containsEntry("path", "src/main/App.java");
        assertThat(toolArguments(fallback)).containsEntry("path", "README.md");
    }

    @Test
    void listCommandDefaultsToWorkspaceRoot() {
        FakeTurn root = DemoTurnScript.next(request(user("/ls")));
        FakeTurn nested = DemoTurnScript.next(request(user("列出目录 src")));

        assertThat(toolName(root)).isEqualTo("ls");
        assertThat(toolArguments(root)).containsEntry("path", ".");
        assertThat(toolArguments(nested)).containsEntry("path", "src");
    }

    @Test
    void writeCommandProducesWriteToolCallWithEscapedNewlines() {
        FakeTurn turn = DemoTurnScript.next(
                request(user("/write notes.txt 第一行\\n第二行")));

        assertThat(turn.response().stopReason()).isEqualTo(AssistantStopReason.TOOL_USE);
        assertThat(toolName(turn)).isEqualTo("write");
        assertThat(toolArguments(turn))
                .containsEntry("path", "notes.txt")
                .containsEntry("content", "第一行\n第二行");
    }

    @Test
    void writeCommandSupportsQuotedPathAndEmptyContent() {
        FakeTurn quoted = DemoTurnScript.next(request(user("写文件 \"docs/note.md\" 你好")));
        FakeTurn empty = DemoTurnScript.next(request(user("/write empty.txt")));

        assertThat(toolArguments(quoted))
                .containsEntry("path", "docs/note.md")
                .containsEntry("content", "你好");
        assertThat(toolArguments(empty)).containsEntry("content", "");
    }

    @Test
    void toolCommandProducesArbitraryToolCall() {
        FakeTurn turn = DemoTurnScript.next(
                request(user("/tool app.test.sample.echo {\"text\":\"你好\"}")));

        assertThat(turn.response().stopReason()).isEqualTo(AssistantStopReason.TOOL_USE);
        assertThat(toolName(turn)).isEqualTo("app.test.sample.echo");
        assertThat(toolArguments(turn)).containsEntry("text", "你好");
    }

    @Test
    void toolCommandWithoutArgumentsSendsEmptyObject() {
        FakeTurn turn = DemoTurnScript.next(request(user("/tool example.ping")));

        assertThat(toolName(turn)).isEqualTo("example.ping");
        assertThat(toolArguments(turn)).isEmpty();
    }

    @Test
    void invalidToolArgumentsReturnHint() {
        FakeTurn turn = DemoTurnScript.next(request(user("/tool example.echo not-json")));

        assertThat(turn.response().stopReason()).isEqualTo(AssistantStopReason.STOP);
        assertThat(text(turn)).contains("JSON 对象").contains("/tool");
    }

    @Test
    void toolResultBecomesTextSummary() {
        FakeTurn turn = DemoTurnScript.next(request(
                user("/read hello.txt"), toolResult("hello\nworld", false)));

        assertThat(turn.response().stopReason()).isEqualTo(AssistantStopReason.STOP);
        assertThat(turn.response().content()).hasSize(1);
        assertThat(text(turn)).contains("工具 `read` 已返回结果").contains("hello\nworld");
        assertThat(turn.deltas()).hasSize(1);
    }

    @Test
    void errorResultIsMarkedAsError() {
        FakeTurn turn = DemoTurnScript.next(request(
                user("/read missing.txt"), toolResult("文件不存在", true)));

        assertThat(text(turn)).contains("工具 `read` 已返回错误").contains("文件不存在");
    }

    @Test
    void longResultIsTruncated() {
        String content = "x".repeat(PREVIEW_LENGTH + 500);
        FakeTurn turn = DemoTurnScript.next(request(user("/read big.txt"),
                toolResult(content, false)));

        assertThat(text(turn))
                .contains("x".repeat(PREVIEW_LENGTH))
                .contains("…（结果已截断）")
                .doesNotContain("x".repeat(PREVIEW_LENGTH + 1));
    }

    @Test
    void plainInputReturnsDemoHint() {
        FakeTurn turn = DemoTurnScript.next(request(user("你好")));

        assertThat(turn.response().stopReason()).isEqualTo(AssistantStopReason.STOP);
        assertThat(text(turn))
                .contains("我收到：你好")
                .contains("/read README.md")
                .contains("/write notes.txt");
    }

    @Test
    void imageOnlyInputIsAcknowledgedWithCount() {
        FakeTurn turn = DemoTurnScript.next(request(imageUser("", 2)));

        assertThat(turn.response().stopReason()).isEqualTo(AssistantStopReason.STOP);
        assertThat(text(turn))
                .contains("我收到：2 张图片")
                .contains("不会真正解析图片内容");
    }

    @Test
    void textWithImagesAppendsImageCount() {
        FakeTurn turn = DemoTurnScript.next(request(imageUser("看图", 1)));

        assertThat(text(turn))
                .contains("我收到：看图（另附 1 张图片）")
                .contains("/read README.md");
    }

    private String text(FakeTurn turn) {
        return turn.response().content().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .reduce("", String::concat);
    }

    private String toolName(FakeTurn turn) {
        return turn.response().content().stream()
                .filter(ToolCallBlock.class::isInstance)
                .map(ToolCallBlock.class::cast)
                .findFirst()
                .map(ToolCallBlock::name)
                .orElse("");
    }

    private Map<String, Object> toolArguments(FakeTurn turn) {
        return turn.response().content().stream()
                .filter(ToolCallBlock.class::isInstance)
                .map(ToolCallBlock.class::cast)
                .findFirst()
                .orElseThrow()
                .arguments();
    }

    private LlmRequest request(AgentMessage... messages) {
        ModelEndpoint endpoint = new ModelEndpoint(ProviderType.FAKE, "", "", "fake-coder",
                16_000, 2_000, ModelCapabilities.defaults());
        ModelProfile profile = ModelProfile.create(new ModelId("fake-demo"), "Demo", endpoint);
        return new LlmRequest(profile, "", List.of(messages), List.of(), ThinkingLevel.OFF);
    }

    private UserMessage user(String text) {
        return new UserMessage(List.of(new TextBlock(text)), NOW);
    }

    private UserMessage imageUser(String text, int count) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!text.isBlank()) {
            blocks.add(new TextBlock(text));
        }
        for (int index = 0; index < count; index++) {
            blocks.add(new ImageBlock("att-" + index, "image/png", "image" + index + ".png"));
        }
        return new UserMessage(blocks, NOW);
    }

    private ToolResultMessage toolResult(String content, boolean error) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock(content));
        return new ToolResultMessage("call-demo", "read", blocks, error, Map.of(), NOW);
    }
}
