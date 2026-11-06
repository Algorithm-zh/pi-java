package com.piagent.app;

import java.util.Set;

/**
 * 本地单用户模式的远端绑定保护。
 *
 * <p>没有登录与权限边界后，端口可达即等于可以执行工具。默认只监听回环地址；
 * 显式绑定到其他地址时必须设置 {@code PI_ALLOW_REMOTE=true}，避免用户无意中
 * 把 agent 暴露到局域网或公网。</p>
 */
final class RemoteBindingGuard {

    private static final Set<String> LOOPBACK = Set.of(
            "127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1");

    private RemoteBindingGuard() {
    }

    /**
     * 校验启动参数与环境变量。
     *
     * @param args 启动参数
     * @throws IllegalStateException 绑定非回环地址但未显式允许
     */
    static void verify(String[] args) {
        String address = address(args);
        if (LOOPBACK.contains(address.toLowerCase()) || allowRemote()) {
            return;
        }
        throw new IllegalStateException(
                "server.address=" + address + " 会监听非回环地址。pi-java 没有登录与权限边界，"
                        + "端口可达即可执行工具；如确认需要远程访问，请设置 PI_ALLOW_REMOTE=true。");
    }

    private static String address(String[] args) {
        String prefix = "--server.address=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length()).strip();
            }
        }
        String fromEnv = System.getenv("PI_SERVER_ADDRESS");
        return fromEnv == null || fromEnv.isBlank() ? "127.0.0.1" : fromEnv.strip();
    }

    private static boolean allowRemote() {
        return "true".equalsIgnoreCase(System.getenv("PI_ALLOW_REMOTE"));
    }
}
