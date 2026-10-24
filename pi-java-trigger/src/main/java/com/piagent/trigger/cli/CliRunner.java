package com.piagent.trigger.cli;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.InputStream;
import java.io.PrintWriter;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

/**
 * CLI 入口：组装命令树、注入标准流并统一错误退出码。
 *
 * <p>该组件只在 CLI 模式被调用；Web 模式不经过它，因此不会影响 HTTP 触发层。</p>
 */
@Component
public class CliRunner {

    private final ApplicationContext context;

    /**
     * 创建 CLI 入口。
     *
     * @param context Spring 容器
     */
    public CliRunner(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 执行一次命令行。
     *
     * @param args 参数
     * @param in 标准输入
     * @param out 标准输出
     * @param err 标准错误
     * @return 退出码
     */
    public int execute(String[] args, InputStream in, PrintWriter out, PrintWriter err) {
        CliTerminal.bind(in);
        try {
            CommandLine commandLine = new CommandLine(PiRootCommand.class, new SpringCliFactory(context));
            commandLine.setOut(out);
            commandLine.setErr(err);
            commandLine.setExecutionExceptionHandler((error, cmd, parseResult) -> {
                PrintWriter target = cmd.getErr();
                target.println("错误: " + message(error));
                target.flush();
                return exitCode(error);
            });
            int code = commandLine.execute(args);
            out.flush();
            err.flush();
            return code;
        } finally {
            CliTerminal.unbind();
        }
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static int exitCode(Exception error) {
        if (error instanceof DomainException domain && domain.code() == DomainErrorCode.INVALID_ARGUMENT) {
            return 2;
        }
        if (error instanceof IllegalArgumentException) {
            return 2;
        }
        return 1;
    }
}
