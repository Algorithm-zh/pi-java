package com.piagent.infrastructure.git;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 执行单条 git 命令：固定参数、无 shell、超时终止进程树、输出限长。
 */
final class GitCommandRunner {

    private static final int MAX_STDOUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 64 * 1024;
    private static final Duration DESTROY_WAIT = Duration.ofSeconds(2);

    private final Duration timeout;

    GitCommandRunner(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * 执行命令。
     *
     * @param directory 工作目录
     * @param environment 追加的环境变量
     * @param args git 子命令与参数
     * @return 执行结果
     */
    Result run(Path directory, Map<String, String> environment, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(false);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().putAll(environment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.GIT_UNAVAILABLE,
                    "无法执行 git，请确认已安装并加入 PATH", error);
        }
        AtomicReference<Output> stdout = new AtomicReference<>();
        AtomicReference<Output> stderr = new AtomicReference<>();
        Thread outReader = Thread.ofVirtual().start(
                () -> stdout.set(read(process.getInputStream(), MAX_STDOUT_BYTES)));
        Thread errReader = Thread.ofVirtual().start(
                () -> stderr.set(read(process.getErrorStream(), MAX_STDERR_BYTES)));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            destroy(process);
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED, "等待 git 命令时被中断", error);
        }
        if (!finished) {
            destroy(process);
            joinQuietly(outReader);
            joinQuietly(errReader);
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "git 命令超时（" + timeout.toSeconds() + " 秒）: " + String.join(" ", args));
        }
        joinQuietly(outReader);
        joinQuietly(errReader);
        Output out = stdout.get();
        Output err = stderr.get();
        return new Result(process.exitValue(),
                out == null ? "" : out.text(), err == null ? "" : err.text(),
                (out != null && out.truncated()) || (err != null && err.truncated()));
    }

    private static Output read(InputStream stream, int limit) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        boolean truncated = false;
        try (stream) {
            int count;
            while ((count = stream.read(chunk)) > 0) {
                int remaining = limit - buffer.size();
                if (remaining > 0) {
                    buffer.write(chunk, 0, Math.min(count, remaining));
                }
                if (count > remaining) {
                    truncated = true;
                }
            }
        } catch (IOException error) {
            truncated = true;
        }
        return new Output(buffer.toString(StandardCharsets.UTF_8), truncated);
    }

    private static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(DESTROY_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(DESTROY_WAIT.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 命令结果。
     *
     * @param exitCode 退出码
     * @param stdout 标准输出
     * @param stderr 标准错误
     * @param truncated 输出是否被截断
     */
    record Result(int exitCode, String stdout, String stderr, boolean truncated) {

        boolean success() {
            return exitCode == 0;
        }

        String out() {
            return stdout.strip();
        }

        String errorLine() {
            String text = stderr.strip();
            int newline = text.indexOf('\n');
            return newline < 0 ? text : text.substring(0, newline);
        }
    }

    private record Output(String text, boolean truncated) {
    }
}
