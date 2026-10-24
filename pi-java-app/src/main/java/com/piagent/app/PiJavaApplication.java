package com.piagent.app;

import com.piagent.app.cli.CliLauncher;
import com.piagent.trigger.cli.CliMode;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * pi-java 的唯一启动入口与装配点。
 *
 * <p>本模块只负责“把零件接起来”：组件扫描覆盖 {@code com.piagent} 下的
 * trigger、infrastructure 与 application Bean，自身不承载业务逻辑。
 * Web 与 CLI 共用同一个 Spring 容器，CLI 通过命令模式复用应用层用例。</p>
 *
 * <p>启动模式由 {@link CliMode} 判定：无参数、{@code serve} 或 Spring 参数
 * 启动 Web；CLI 子命令以非 Web 容器执行。</p>
 */
@SpringBootApplication(scanBasePackages = "com.piagent")
public class PiJavaApplication {

    /**
     * 启动 Web 服务或执行 CLI 命令。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        if (CliMode.isCli(args)) {
            System.exit(CliLauncher.run(args));
            return;
        }
        if (CliMode.serveHelpRequested(args)) {
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
            out.println(CliMode.serveUsage());
            return;
        }
        SpringApplication.run(PiJavaApplication.class, CliMode.webArgs(args));
    }
}
