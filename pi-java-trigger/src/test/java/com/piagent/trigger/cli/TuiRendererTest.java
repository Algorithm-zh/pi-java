package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.SessionId;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TuiRendererTest {

    private static final String ID = "01J00000000000000000000001";

    @Test
    void streamsTextAndKeepsThinkingOutOfFinalText() {
        StringWriter buffer = new StringWriter();
        TuiRenderer renderer = new TuiRenderer(new PrintWriter(buffer, true), new Ansi(false));
        renderer.render(event(RunEventType.MESSAGE_STARTED, Map.of("role", "assistant")));
        renderer.render(event(RunEventType.MESSAGE_DELTA,
                Map.of("kind", "thinking", "thinking", "先想一想")));
        renderer.render(event(RunEventType.MESSAGE_DELTA,
                Map.of("kind", "text", "text", "你好")));
        renderer.render(event(RunEventType.MESSAGE_COMPLETED, Map.of("stopReason", "STOP")));
        renderer.finish();

        assertThat(renderer.finalText()).isEqualTo("你好");
        assertThat(buffer.toString()).contains("· 思考 先想一想").contains("● 你好");
    }

    @Test
    void toolAndApprovalEventsBecomeShortLines() {
        StringWriter buffer = new StringWriter();
        TuiRenderer renderer = new TuiRenderer(new PrintWriter(buffer, true), new Ansi(false));
        renderer.render(event(RunEventType.TOOL_STARTED,
                Map.of("toolName", "write", "arguments", "{\"path\":\"a.txt\"}")));
        renderer.render(event(RunEventType.TOOL_COMPLETED,
                Map.of("toolName", "write", "isError", false, "content", "第一行\n第二行")));
        renderer.render(event(RunEventType.APPROVAL_RESOLVED,
                Map.of("toolName", "write", "status", "DENIED", "reason", "cli-denied")));

        String output = buffer.toString();
        assertThat(output)
                .contains("● write")
                .contains("└ 完成 第一行 第二行")
                .contains("× 已拒绝 write · cli-denied");
        assertThat(output.lines()).allMatch(line -> line.length() < 140);
    }

    @Test
    void longToolArgumentsAreTruncated() {
        StringWriter buffer = new StringWriter();
        TuiRenderer renderer = new TuiRenderer(new PrintWriter(buffer, true), new Ansi(false));
        renderer.render(event(RunEventType.TOOL_STARTED,
                Map.of("toolName", "read", "arguments", "x".repeat(400))));

        assertThat(buffer.toString()).contains("…");
        assertThat(buffer.toString().lines().findFirst().orElseThrow()).hasSizeLessThan(80);
    }

    private static RunEvent event(RunEventType type, Map<String, Object> data) {
        return new RunEvent(1, RunId.parse(ID), SessionId.parse(ID), type, Instant.now(), data);
    }
}
