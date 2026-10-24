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
import picocli.CommandLine;
import picocli.CommandLine.Command;

class CliRunRendererTest {

    private static final String ID = "01J00000000000000000000001";

    @Test
    void streamsTextAndKeepsThinkingOutOfFinalText() {
        StringWriter buffer = new StringWriter();
        CliRunRenderer renderer = renderer(buffer, false);
        renderer.render(event(RunEventType.MESSAGE_STARTED, Map.of("role", "assistant")));
        renderer.render(event(RunEventType.MESSAGE_DELTA,
                Map.of("kind", "thinking", "thinking", "先想一想")));
        renderer.render(event(RunEventType.MESSAGE_DELTA,
                Map.of("kind", "text", "text", "你好")));
        renderer.render(event(RunEventType.MESSAGE_COMPLETED,
                Map.of("stopReason", "STOP")));
        renderer.finish();

        assertThat(renderer.finalText()).isEqualTo("你好");
        assertThat(buffer.toString()).contains("[思考] 先想一想").contains("你好");
    }

    @Test
    void jsonModeDoesNotWriteStreamingOutput() {
        StringWriter buffer = new StringWriter();
        CliRunRenderer renderer = renderer(buffer, true);
        renderer.render(event(RunEventType.MESSAGE_STARTED, Map.of("role", "assistant")));
        renderer.render(event(RunEventType.MESSAGE_DELTA,
                Map.of("kind", "text", "text", "答案")));
        renderer.render(event(RunEventType.MESSAGE_COMPLETED,
                Map.of("stopReason", "STOP")));
        renderer.finish();

        assertThat(renderer.finalText()).isEqualTo("答案");
        assertThat(buffer.toString()).isEmpty();
    }

    @Test
    void toolEventsBecomeSingleLineSummaries() {
        StringWriter buffer = new StringWriter();
        CliRunRenderer renderer = renderer(buffer, false);
        renderer.render(event(RunEventType.TOOL_STARTED,
                Map.of("toolName", "read", "arguments", "{\"path\":\"a.txt\"}")));
        renderer.render(event(RunEventType.TOOL_COMPLETED,
                Map.of("toolName", "read", "isError", false, "content", "第一行\n第二行")));

        assertThat(buffer.toString())
                .contains("[工具] read")
                .contains("完成")
                .contains("第一行 第二行");
    }

    private static CliRunRenderer renderer(StringWriter buffer, boolean json) {
        PrintWriter writer = new PrintWriter(buffer, true);
        CommandLine commandLine = new CommandLine(new NoopCommand());
        commandLine.setOut(writer);
        commandLine.setErr(writer);
        return new CliRunRenderer(CliTerminal.of(commandLine), json);
    }

    private static RunEvent event(RunEventType type, Map<String, Object> data) {
        return new RunEvent(1, RunId.parse(ID), SessionId.parse(ID), type, Instant.now(), data);
    }

    @Command(name = "noop")
    private static final class NoopCommand implements Runnable {
        @Override
        public void run() {
        }
    }
}
