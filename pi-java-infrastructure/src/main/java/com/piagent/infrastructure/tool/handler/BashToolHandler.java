package com.piagent.infrastructure.tool.handler;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.service.ToolArgumentGuard;
import com.piagent.infrastructure.tool.BuiltInToolHandler;
import com.piagent.infrastructure.tool.RealPathResolver;
import com.piagent.infrastructure.tool.ToolText;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@code bash} 工具：在工作区内执行一条 shell 命令。
 *
 * <p>命令通过系统 shell 运行（Windows {@code cmd.exe}，其他平台 {@code /bin/sh}），
 * 合并 stdout/stderr。输出只保留前 {@value #DEFAULT_MAX_OUTPUT_CHARS} 个字符，
 * 但读取线程会继续消费剩余字节，避免子进程因管道写满而阻塞。超时或线程中断时
 * 先终止整棵进程树，再返回错误结果，防止孤儿进程继续运行。</p>
 *
 * <p>这是最高风险工具：默认策略为 {@code CRITICAL -> DENY}，需要用户在策略中
 * 显式放行或询问才会执行。</p>
 */
public final class BashToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("command", "cwd",
            "timeoutSeconds");
    private static final int MAX_COMMAND_LENGTH = 10_000;
    private static final int MAX_CWD_LENGTH = 1_024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 60_000;
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private final RealPathResolver resolver;
    private final int maxOutputChars;

    /**
     * 创建 bash 工具。
     *
     * @param resolver 真实路径解析器
     */
    public BashToolHandler(RealPathResolver resolver) {
        this(resolver, DEFAULT_MAX_OUTPUT_CHARS);
    }

    BashToolHandler(RealPathResolver resolver, int maxOutputChars) {
        this.resolver = Objects.requireNonNull(resolver, "resolver 不能为空");
        if (maxOutputChars < 1) {
            throw new IllegalArgumentException("maxOutputChars 必须为正数");
        }
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        ToolArgumentGuard.rejectUnknown(arguments, ALLOWED_ARGUMENTS);
        String command = ToolArgumentGuard.requireString(arguments, "command",
                MAX_COMMAND_LENGTH);
        String requestedCwd = ToolArgumentGuard.optionalString(arguments, "cwd", ".",
                MAX_CWD_LENGTH);
        int timeoutSeconds = ToolArgumentGuard.optionalInt(arguments, "timeoutSeconds",
                DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);
        Path cwd = resolver.resolveReadable(invocation.sandbox(), requestedCwd);
        if (!Files.isDirectory(cwd)) {
            throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT,
                    "cwd 不是目录: " + requestedCwd);
        }
        ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        Process process = start(builder, command);
        OutputCollector output = new OutputCollector(maxOutputChars);
        Thread drainer = Thread.ofVirtual().name("pi-bash-output")
                .start(() -> output.drain(process.getInputStream()));
        long started = System.nanoTime();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            terminate(process);
            join(drainer);
            Thread.currentThread().interrupt();
            return interrupted(invocation, cwd, elapsedMillis(started), output);
        }
        if (!finished) {
            terminate(process);
            join(drainer);
            return timedOut(invocation, cwd, timeoutSeconds, elapsedMillis(started), output);
        }
        join(drainer);
        return completed(invocation, cwd, process.exitValue(), elapsedMillis(started), output);
    }

    private Process start(ProcessBuilder builder, String command) {
        try {
            return builder.start();
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "启动命令失败: " + error.getMessage(), error);
        }
    }

    private ToolResult completed(ToolInvocation invocation, Path cwd, int exitCode,
            long durationMs, OutputCollector output) {
        String text = renderOutput(output);
        Map<String, Object> details = details(cwd, exitCode, durationMs, false,
                output.truncated(), invocation.sandbox().workspaceRoot());
        if (exitCode == 0) {
            return ToolResult.success(invocation.callId(), invocation.tool(),
                    text.isEmpty() ? "（命令执行成功，无输出）" : text, details);
        }
        String content = text.isEmpty() ? "（命令无输出）" : text;
        return ToolResult.error(invocation.callId(), invocation.tool(),
                content + "\n（退出码: " + exitCode + "）", details);
    }

    private ToolResult timedOut(ToolInvocation invocation, Path cwd, int timeoutSeconds,
            long durationMs, OutputCollector output) {
        String text = renderOutput(output);
        String message = "命令执行超时（" + timeoutSeconds + " 秒），已终止进程树";
        String content = text.isEmpty() ? message : text + "\n\n" + message;
        Map<String, Object> details = details(cwd, -1, durationMs, true,
                output.truncated(), invocation.sandbox().workspaceRoot());
        return ToolResult.error(invocation.callId(), invocation.tool(), content, details);
    }

    private ToolResult interrupted(ToolInvocation invocation, Path cwd, long durationMs,
            OutputCollector output) {
        String text = renderOutput(output);
        String message = "命令执行被中断，已终止进程树";
        String content = text.isEmpty() ? message : text + "\n\n" + message;
        Map<String, Object> details = details(cwd, -1, durationMs, false,
                output.truncated(), invocation.sandbox().workspaceRoot());
        return ToolResult.error(invocation.callId(), invocation.tool(), content, details);
    }

    private Map<String, Object> details(Path cwd, int exitCode, long durationMs,
            boolean timedOut, boolean truncated, Path workspaceRoot) {
        Map<String, Object> details = new HashMap<>();
        details.put("cwd", ToolText.relative(workspaceRoot, cwd));
        details.put("exitCode", exitCode);
        details.put("durationMs", durationMs);
        details.put("timedOut", timedOut);
        details.put("truncated", truncated);
        return details;
    }

    private String renderOutput(OutputCollector output) {
        String text = output.text();
        return output.truncated() ? text + "\n…（输出已截断）" : text;
    }

    private List<String> shellCommand(String command) {
        return WINDOWS
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("/bin/sh", "-c", command);
    }

    private void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
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

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /**
     * 边读边限长：存储达到上限后继续消费但不保留，防止管道阻塞。
     */
    private static final class OutputCollector {

        private final StringBuilder text = new StringBuilder();
        private final int maxChars;
        private boolean truncated;

        private OutputCollector(int maxChars) {
            this.maxChars = maxChars;
        }

        private void drain(InputStream input) {
            char[] buffer = new char[4_096];
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    append(buffer, read);
                }
            } catch (IOException ignored) {
                // 进程被终止时管道关闭，已读取的内容仍然有效。
            }
        }

        private synchronized void append(char[] buffer, int length) {
            int remaining = maxChars - text.length();
            if (remaining > 0) {
                text.append(buffer, 0, Math.min(remaining, length));
            }
            if (length > remaining) {
                truncated = true;
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
