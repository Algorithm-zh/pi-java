package com.piagent.infrastructure.verify;

import com.piagent.domain.verify.model.VerificationOutcome;
import com.piagent.domain.verify.port.VerificationRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 通过系统 shell 执行验证命令。
 *
 * <p>与 {@code bash} 工具一样合并 stdout/stderr、保持管道持续排空，并在超时后
 * 终止整棵进程树。输出只保留前 {@code maxOutputChars} 个字符，但读取线程会继续
 * 消费剩余内容，避免子进程因为管道写满而卡死。</p>
 */
public final class ShellVerificationRunner implements VerificationRunner {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    @Override
    public VerificationOutcome run(String command, Path workspace, Duration timeout,
            int maxOutputChars) {
        Objects.requireNonNull(command, "command 不能为空");
        Objects.requireNonNull(workspace, "workspace 不能为空");
        Objects.requireNonNull(timeout, "timeout 不能为空");
        ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            return new VerificationOutcome(command, null, false, false, elapsedMillis(started),
                    "无法启动验证命令: " + error.getMessage());
        }
        OutputCollector output = new OutputCollector(maxOutputChars);
        Thread drainer = Thread.ofVirtual().name("pi-verify-output")
                .start(() -> output.drain(process.getInputStream()));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            terminate(process);
            join(drainer);
            Thread.currentThread().interrupt();
            return new VerificationOutcome(command, null, true, output.truncated(),
                    elapsedMillis(started), appendNote(output.text(), "验证命令被中断"));
        }
        if (!finished) {
            terminate(process);
            join(drainer);
            return new VerificationOutcome(command, null, true, output.truncated(),
                    elapsedMillis(started), appendNote(output.text(),
                            "验证命令超时（" + timeout.toSeconds() + " 秒），已终止进程树"));
        }
        join(drainer);
        return new VerificationOutcome(command, process.exitValue(), false, output.truncated(),
                elapsedMillis(started), output.text());
    }

    private List<String> shellCommand(String command) {
        return WINDOWS ? List.of("cmd.exe", "/c", command) : List.of("/bin/sh", "-c", command);
    }

    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void join(Thread drainer) {
        try {
            drainer.join(2_000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private String appendNote(String text, String note) {
        return text.isBlank() ? note : text + "\n\n" + note;
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /**
     * 限量收集输出，但持续排空管道。
     */
    private static final class OutputCollector {

        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        private OutputCollector(int limit) {
            this.limit = Math.max(1, limit);
        }

        private void drain(InputStream stream) {
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[8_192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    append(buffer, read);
                }
            } catch (IOException ignored) {
                // 进程被终止时流可能提前关闭；已读取的内容仍然可用。
            }
        }

        private synchronized void append(char[] buffer, int read) {
            int room = limit - text.length();
            if (room <= 0) {
                truncated = true;
                return;
            }
            if (read > room) {
                text.append(buffer, 0, room);
                truncated = true;
            } else {
                text.append(buffer, 0, read);
            }
        }

        private synchronized String text() {
            return text.toString();
        }

        private synchronized boolean truncated() {
            return truncated;
        }
    }
}
