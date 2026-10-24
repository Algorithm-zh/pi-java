package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

class TuiApprovalTest {

    private static final Map<String, Object> WRITE = Map.of(
            "toolName", "write",
            "riskLevel", "HIGH",
            "arguments", "{\"path\":\"notes.txt\"}");

    @Test
    void approvingDrawsFriendlyPanelWithChineseRiskLabel() {
        StringWriter buffer = new StringWriter();
        ApprovalPrompt.Answer answer = ask(buffer, new Ansi(false), "y");

        assertThat(answer.approved()).isTrue();
        assertThat(answer.noInput()).isFalse();
        String output = buffer.toString();
        assertThat(output)
                .contains("需要你确认")
                .contains("write")
                .contains("高风险")
                .contains("它准备这样做")
                .contains("允许执行？")
                .contains("直接回车 = 拒绝")
                .doesNotContain("HIGH");
        assertThat(borderWidths(output)).hasSize(2).containsOnly(44);
    }

    @Test
    void enterAndUnknownInputDeny() {
        assertThat(ask(new StringWriter(), new Ansi(false), "").approved()).isFalse();
        assertThat(ask(new StringWriter(), new Ansi(false), "n").approved()).isFalse();
    }

    @Test
    void acceptsChineseConfirmation() {
        assertThat(ask(new StringWriter(), new Ansi(false), "是").approved()).isTrue();
        assertThat(ask(new StringWriter(), new Ansi(false), "好").approved()).isTrue();
    }

    @Test
    void interruptBecomesMissingInput() {
        StringWriter buffer = new StringWriter();
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenThrow(new UserInterruptException("^C"));

        ApprovalPrompt.Answer answer = new TuiApproval(new PrintWriter(buffer, true), reader,
                new Ansi(false), interrupts()).ask(WRITE);

        assertThat(answer.approved()).isFalse();
        assertThat(answer.noInput()).isTrue();
        assertThat(buffer.toString()).contains("未输入，按拒绝处理");
    }

    @Test
    void endOfFileAlsoBecomesMissingInput() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenThrow(new EndOfFileException());

        ApprovalPrompt.Answer answer = new TuiApproval(new PrintWriter(new StringWriter(), true),
                reader, new Ansi(false), interrupts()).ask(WRITE);

        assertThat(answer.noInput()).isTrue();
    }

    @Test
    void colorsBorderAndHighRisk() {
        StringWriter buffer = new StringWriter();
        ask(buffer, new Ansi(true), "y");

        assertThat(buffer.toString())
                .contains("\u001B[38;5;179m╭─")
                .contains("\u001B[31m高风险");
    }

    private static ApprovalPrompt.Answer ask(StringWriter buffer, Ansi ansi, String input) {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn(input);
        return new TuiApproval(new PrintWriter(buffer, true), reader, ansi, interrupts()).ask(WRITE);
    }

    private static TuiInterrupts interrupts() {
        Terminal terminal = mock(Terminal.class);
        return TuiInterrupts.start(terminal, mock(CliRun.class), new PrintWriter(new StringWriter(), true));
    }

    private static java.util.List<Integer> borderWidths(String output) {
        return output.lines()
                .filter(line -> line.contains("╭─") || line.contains("╰"))
                .map(Ansi::width)
                .toList();
    }
}
