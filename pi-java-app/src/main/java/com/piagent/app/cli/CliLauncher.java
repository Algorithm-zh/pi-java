package com.piagent.app.cli;

import com.piagent.app.PiJavaApplication;
import com.piagent.trigger.cli.CliRunner;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
 * <p>输出固定 UTF-8：Windows 重定向到管道时 {@code System.out.charset()} 会退回 GBK，
 * 与现代终端、日志和 Web 的编码不一致，导致中文乱码。</p>
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
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(consoleOut, StandardCharsets.UTF_8), true);
                PrintWriter err = new PrintWriter(
                        new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
                return runner.execute(args, System.in, out, err);
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
