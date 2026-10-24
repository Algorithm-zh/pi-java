package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

/**
 * 验证 run 期间 Ctrl+C 的取消路径。
 *
 * <p>JLine 在 Windows 上把 Ctrl+C 转成 {@link Terminal.Signal#INT} 而不是字节，
 * 所以测试直接触发信号，覆盖真实终端里的同一条链路。</p>
 */
class TuiInterruptsTest {

    @Test
    void abortsOnlyOncePerRun() throws Exception {
        CliRun run = mock(CliRun.class);
        StringWriter sink = new StringWriter();
        try (Terminal terminal = terminal()) {
            TuiInterrupts interrupts = TuiInterrupts.start(terminal, run, new PrintWriter(sink));
            terminal.raise(Terminal.Signal.INT);
            terminal.raise(Terminal.Signal.INT);
            interrupts.close();
            verify(run, times(1)).abort();
            assertThat(sink.toString()).contains("[取消]");
        }
    }

    @Test
    void ignoresSignalWhilePausedAndResumes() throws Exception {
        CliRun run = mock(CliRun.class);
        StringWriter sink = new StringWriter();
        try (Terminal terminal = terminal()) {
            TuiInterrupts interrupts = TuiInterrupts.start(terminal, run, new PrintWriter(sink));
            interrupts.pause();
            terminal.raise(Terminal.Signal.INT);
            verify(run, never()).abort();
            interrupts.resume();
            terminal.raise(Terminal.Signal.INT);
            verify(run, times(1)).abort();
            interrupts.close();
        }
    }

    private static Terminal terminal() throws Exception {
        return new DumbTerminal("test", "xterm-256color",
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                StandardCharsets.UTF_8);
    }
}
