package com.piagent.infrastructure.mcp;

import com.piagent.infrastructure.config.McpSettings;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 测试配置构造器。
 */
final class McpTestServers {

    private McpTestServers() {
    }

    static McpSettings.Server stdio(String name, String command, List<String> args,
            Duration requestTimeout) {
        return new McpSettings.Server(name, "stdio",
                new McpSettings.Stdio(command, args, Map.of()), null,
                new McpSettings.Timeouts(requestTimeout, Duration.ofSeconds(10)),
                new McpSettings.Risks("auto", Map.of()));
    }

    static McpSettings.Server http(String name, String url) {
        return new McpSettings.Server(name, "http", null,
                new McpSettings.Http(url, Map.of()),
                new McpSettings.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new McpSettings.Risks("auto", Map.of()));
    }

    static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    static String classpath() {
        return System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
    }

    static List<String> fixtureArgs(Class<?> fixture) {
        return List.of("-cp", classpath(), fixture.getName());
    }
}
