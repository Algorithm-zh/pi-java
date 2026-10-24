package com.piagent.trigger.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * 可执行 jar 的启动模式判定。
 *
 * <p>默认启动 Web，保持历史用法：无参数或直接传 Spring 参数（如
 * {@code --server.port=9090}）都启动服务。只有明确的 CLI 子命令或
 * 根级帮助/版本才进入非 Web 模式。</p>
 */
public final class CliMode {

    private static final String SERVE = "serve";
    private static final String PORT = "--port";
    private static final String PROFILE = "--profile";

    private CliMode() {
    }

    /**
     * 是否进入 CLI 模式。
     *
     * @param args 启动参数
     * @return true 表示执行 CLI 命令
     */
    public static boolean isCli(String[] args) {
        if (args.length == 0) {
            return false;
        }
        String first = args[0];
        if (SERVE.equals(first)) {
            return false;
        }
        if (!first.startsWith("-")) {
            return true;
        }
        return switch (first) {
            case "--help", "-h", "--version", "-V" -> true;
            default -> false;
        };
    }

    /**
     * 转换 {@code serve} 的参数为 Spring Boot 参数。
     *
     * @param args 原始参数
     * @return Web 启动参数
     */
    public static String[] webArgs(String[] args) {
        if (args.length == 0 || !SERVE.equals(args[0])) {
            return args;
        }
        List<String> result = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            String arg = args[index];
            if (PORT.equals(arg) && index + 1 < args.length) {
                result.add("--server.port=" + args[++index]);
            } else if (arg.startsWith(PORT + "=")) {
                result.add("--server.port=" + arg.substring(PORT.length() + 1));
            } else if (PROFILE.equals(arg) && index + 1 < args.length) {
                result.add("--spring.profiles.active=" + args[++index]);
            } else if (arg.startsWith(PROFILE + "=")) {
                result.add("--spring.profiles.active=" + arg.substring(PROFILE.length() + 1));
            } else {
                result.add(arg);
            }
        }
        return result.toArray(String[]::new);
    }

    /**
     * 是否请求 Web 模式帮助。
     *
     * @param args 启动参数
     * @return true 表示 {@code serve --help}
     */
    public static boolean serveHelpRequested(String[] args) {
        if (args.length == 0 || !SERVE.equals(args[0])) {
            return false;
        }
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Web 模式用法说明。
     *
     * @return 多行用法
     */
    public static String serveUsage() {
        return """
                用法: pi serve [--port 8080] [--profile demo|mysql] [Spring 参数]

                Web 模式默认绑定 127.0.0.1:8080，不提供认证。
                无参数启动与 serve 等价；CLI 子命令见 pi --help。""";
    }
}
