package com.piagent.infrastructure.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 服务器配置（{@code pi.mcp.servers}）。
 *
 * <p>MCP 服务器是外部能力来源，不是插件：配置在 application.yml 中，由宿主在启动时
 * 连接并发现工具。敏感值（token、密码）必须写成 {@code ${ENV_VAR}} 占位符，
 * 由 Spring 在启动时从环境变量解析，仓库里不保存明文。</p>
 *
 * <p>结构错误（未知传输、缺少 command/url、非法风险级别）在绑定阶段直接失败，
 * 避免“配置写错但静默不生效”；连接失败只在启动日志中告警，不影响其他服务器。</p>
 *
 * @param servers MCP 服务器列表；缺省为空
 */
@ConfigurationProperties(prefix = "pi.mcp")
public record McpSettings(List<Server> servers) {

    /**
     * 规范化配置：null 列表替换为空列表。
     *
     * @param servers 服务器列表
     */
    public McpSettings {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    /**
     * 空配置，供测试与未启用场景使用。
     *
     * @return 空配置
     */
    public static McpSettings empty() {
        return new McpSettings(List.of());
    }

    /**
     * 传输类型。
     */
    public enum Transport {

        /** 子进程 + 换行分隔 JSON。 */
        STDIO,

        /** Streamable HTTP（JSON 或 SSE 响应）。 */
        HTTP;

        /**
         * 解析配置值。
         *
         * @param value 配置值
         * @return 传输类型
         */
        public static Transport parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "stdio" -> STDIO;
                case "http", "streamable-http" -> HTTP;
                default -> throw new IllegalArgumentException(
                        "MCP 传输只支持 stdio 或 http，实际为: " + value);
            };
        }
    }

    /**
     * 单个 MCP 服务器。
     *
     * @param name 服务器名，同时作为工具命名空间
     * @param transport stdio 或 http
     * @param stdio 子进程参数
     * @param http 远程地址参数
     * @param timeouts 超时
     * @param risks 风险映射
     */
    public record Server(String name, String transport, Stdio stdio, Http http,
            Timeouts timeouts, Risks risks) {

        private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,39}");

        /**
         * 校验并补全默认值。
         *
         * @param name 服务器名
         * @param transport 传输
         * @param stdio 子进程参数
         * @param http 远程地址参数
         * @param timeouts 超时
         * @param risks 风险映射
         */
        public Server {
            name = requireText(name, "MCP 服务器名");
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "MCP 服务器名只能包含小写字母、数字与连字符，且以字母开头: " + name);
            }
            transport = transport == null || transport.isBlank()
                    ? "stdio" : transport.trim().toLowerCase(Locale.ROOT);
            Transport kind = Transport.parse(transport);
            if (kind == Transport.STDIO && stdio == null) {
                throw new IllegalArgumentException("stdio 服务器必须配置 stdio 参数: " + name);
            }
            if (kind == Transport.HTTP && http == null) {
                throw new IllegalArgumentException("http 服务器必须配置 url: " + name);
            }
            timeouts = timeouts == null ? Timeouts.defaults() : timeouts;
            risks = risks == null ? Risks.defaults() : risks;
        }

        /**
         * 传输类型。
         *
         * @return 传输类型
         */
        public Transport kind() {
            return Transport.parse(transport);
        }
    }

    /**
     * stdio 传输参数。
     *
     * @param command 可执行文件；Windows 下建议使用绝对路径或 {@code cmd}
     * @param args 参数
     * @param env 追加的环境变量，可写 {@code ${ENV_VAR}}
     */
    public record Stdio(String command, List<String> args, Map<String, String> env) {

        /**
         * 规范化参数。
         *
         * @param command 命令
         * @param args 参数
         * @param env 环境变量
         */
        public Stdio {
            command = requireText(command, "MCP 启动命令");
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
        }
    }

    /**
     * Streamable HTTP 传输参数。
     *
     * @param url 端点地址
     * @param headers 附加请求头，可写 {@code ${ENV_VAR}}
     */
    public record Http(String url, Map<String, String> headers) {

        /**
         * 规范化参数。
         *
         * @param url 端点
         * @param headers 请求头
         */
        public Http {
            url = requireText(url, "MCP HTTP 地址");
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("MCP HTTP 地址必须以 http:// 或 https:// 开头: " + url);
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * 超时配置。
     *
     * @param request 单次请求超时
     * @param discovery 初始化与工具发现超时
     */
    public record Timeouts(Duration request, Duration discovery) {

        private static final Duration MAX_REQUEST = Duration.ofMinutes(10);
        private static final Duration MAX_DISCOVERY = Duration.ofMinutes(2);

        /**
         * 校验并补全默认值。
         *
         * @param request 请求超时
         * @param discovery 发现超时
         */
        public Timeouts {
            request = request == null ? Duration.ofSeconds(60) : request;
            discovery = discovery == null ? Duration.ofSeconds(20) : discovery;
            requireRange(request, "MCP 请求超时", MAX_REQUEST);
            requireRange(discovery, "MCP 发现超时", MAX_DISCOVERY);
        }

        /**
         * 默认超时。
         *
         * @return 请求 60 秒、发现 20 秒
         */
        public static Timeouts defaults() {
            return new Timeouts(Duration.ofSeconds(60), Duration.ofSeconds(20));
        }
    }

    /**
     * 风险映射配置。
     *
     * @param defaultLevel auto/low/medium/high/critical；auto 使用工具名启发式
     * @param tools 按远程工具名覆盖的风险级别
     */
    public record Risks(String defaultLevel, Map<String, String> tools) {

        private static final Set<String> LEVELS =
                Set.of("auto", "low", "medium", "high", "critical");

        /**
         * 校验并补全默认值。
         *
         * @param defaultLevel 默认级别
         * @param tools 工具覆盖
         */
        public Risks {
            defaultLevel = defaultLevel == null || defaultLevel.isBlank()
                    ? "auto" : defaultLevel.trim().toLowerCase(Locale.ROOT);
            if (!LEVELS.contains(defaultLevel)) {
                throw new IllegalArgumentException("MCP 风险级别不合法: " + defaultLevel);
            }
            tools = tools == null ? Map.of() : Map.copyOf(tools);
            tools.forEach((tool, level) -> {
                if (level == null || !LEVELS.contains(level.trim().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "MCP 工具 " + tool + " 的风险级别不合法: " + level);
                }
            });
        }

        /**
         * 默认风险配置。
         *
         * @return auto + 无覆盖
         */
        public static Risks defaults() {
            return new Risks("auto", Map.of());
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private static void requireRange(Duration value, String field, Duration max) {
        if (value.isZero() || value.isNegative() || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(field + "必须在 0 到 " + max + " 之间");
        }
    }
}
