package com.piagent.app.cli;

import com.piagent.app.PiJavaApplication;
import com.piagent.trigger.cli.CliCharset;
import com.piagent.trigger.cli.CliRunner;
import java.io.PrintStream;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * CLI 进程启动器：以非 Web 模式启动同一个 Spring 容器。
 *
 * <p>启动前把 {@code System.out} 指向 stderr，避免 Spring/Logback 日志污染
 * {@code --json} 的 stdout；命令输出使用保存下来的原始 stdout 写入器。</p>
 *
 * <p>输出编码由 {@link CliCharset} 按终端环境选择：默认跟随原生编码，
 * Git Bash/MSYS 等 UTF-8 环境自动切换 UTF-8，可用 {@code PI_CLI_ENCODING} 覆盖。</p>
 */
public final class CliLauncher {

    private CliLauncher() {
    }

    /**
     * 启动容器并执行 CLI。
     *
     * @param args 命令行参数
     * @return 进程退出码
     */
    public static int run(String[] args) {
        PrintStream consoleOut = System.out;
        System.setOut(System.err);
        try {
            SpringApplicationBuilder builder = new SpringApplicationBuilder(PiJavaApplication.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .properties("spring.main.log-startup-info=false");
            try (ConfigurableApplicationContext context = builder.run(
                    "--logging.level.root=WARN", "--logging.level.com.piagent=WARN")) {
                CliRunner runner = context.getBean(CliRunner.class);
                return runner.execute(args, System.in,
                        CliCharset.writer(consoleOut), CliCharset.writer(System.err));
            }
        } catch (RuntimeException error) {
            System.err.println("pi-java CLI 启动失败: " + rootMessage(error));
            return 1;
        } finally {
            System.setOut(consoleOut);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
