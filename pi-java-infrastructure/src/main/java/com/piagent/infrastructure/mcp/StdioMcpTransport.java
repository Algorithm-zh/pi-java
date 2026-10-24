package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.infrastructure.config.McpSettings;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * stdio 传输：子进程的标准输入输出 + 换行分隔 JSON。
 *
 * <p>MCP stdio 规范要求每条消息是一行 JSON。请求串行化执行：一次只允许一个
 * 在途请求，响应按 id 匹配，期间收到的通知直接丢弃。进程退出、读写失败与超时
 * 都转换成 {@link McpException}，由上层映射为工具错误。</p>
 */
public final class StdioMcpTransport implements McpTransport {

    private static final System.Logger LOG =
            System.getLogger(StdioMcpTransport.class.getName());
    private static final int STDERR_LOG_LIMIT = 20;

    private final String serverName;
    private final ObjectMapper mapper;
    private final Process process;
    private final BufferedWriter writer;
    private final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    /**
     * 启动子进程。
     *
     * @param server 服务器配置
     * @param mapper JSON 映射器
     */
    public StdioMcpTransport(McpSettings.Server server, ObjectMapper mapper) {
        this.serverName = Objects.requireNonNull(server, "server 不能为空").name();
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
        ProcessBuilder builder = new ProcessBuilder(command(server.stdio()));
        builder.environment().putAll(server.stdio().env());
        try {
            this.process = builder.start();
        } catch (IOException error) {
            throw new McpException("MCP_TRANSPORT",
                    "无法启动 MCP 服务器 " + serverName + ": " + error.getMessage(), error);
        }
        this.writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startThread("mcp-stdio-" + serverName, this::readLoop);
        startThread("mcp-stderr-" + serverName, this::drainErrors);
    }

    @Override
    public synchronized JsonNode request(int id, String method, JsonNode params, Duration timeout) {
        if (!isOpen()) {
            throw new McpException("MCP_TRANSPORT", "MCP 服务器未运行: " + serverName);
        }
        ObjectNode request = McpProtocol.request(mapper, id, method, params);
        write(request);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new McpException("MCP_TIMEOUT", "MCP 请求超时: " + method);
            }
            Message message = poll(remaining);
            if (message.failure() != null) {
                throw new McpException("MCP_TRANSPORT", message.failure());
            }
            JsonNode response = message.node();
            if (response.hasNonNull("id") && response.path("id").asInt(-1) == id) {
                return response;
            }
            LOG.log(System.Logger.Level.DEBUG, "忽略 MCP[{0}] 非匹配消息", serverName);
        }
    }

    @Override
    public synchronized void notify(String method, JsonNode params) {
        if (!isOpen()) {
            throw new McpException("MCP_TRANSPORT", "MCP 服务器未运行: " + serverName);
        }
        write(McpProtocol.notification(mapper, method, params));
    }

    @Override
    public boolean isOpen() {
        return !closed && process.isAlive();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.close();
        } catch (IOException error) {
            LOG.log(System.Logger.Level.DEBUG, "关闭 MCP 输入失败: " + error.getMessage());
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        inbox.offer(new Message(null, "MCP 连接已关闭: " + serverName));
    }

    private void write(JsonNode payload) {
        try {
            writer.write(mapper.writeValueAsString(payload));
            writer.newLine();
            writer.flush();
        } catch (IOException error) {
            throw new McpException("MCP_TRANSPORT",
                    "写入 MCP 请求失败: " + serverName + ": " + error.getMessage(), error);
        }
    }

    private Message poll(long remainingNanos) {
        try {
            Message message = inbox.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (message == null) {
                throw new McpException("MCP_TIMEOUT", "MCP 响应超时: " + serverName);
            }
            return message;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP_CANCELLED", "MCP 请求被取消: " + serverName, error);
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parse(line);
            }
            inbox.offer(new Message(null, "MCP 服务器进程已退出: " + serverName));
        } catch (IOException error) {
            inbox.offer(new Message(null, "读取 MCP 输出失败: " + error.getMessage()));
        }
    }

    private void parse(String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(line);
            if (node != null) {
                inbox.offer(new Message(node, null));
            }
        } catch (JsonProcessingException error) {
            LOG.log(System.Logger.Level.WARNING,
                    "忽略 MCP[" + serverName + "] 的非 JSON 输出（" + line.length() + " 字符）");
        }
    }

    private void drainErrors() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (count++ < STDERR_LOG_LIMIT) {
                    LOG.log(System.Logger.Level.DEBUG,
                            "MCP[" + serverName + "] stderr: " + line);
                }
            }
        } catch (IOException error) {
            LOG.log(System.Logger.Level.DEBUG, "读取 MCP stderr 失败: " + error.getMessage());
        }
    }

    private static void startThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static List<String> command(McpSettings.Stdio stdio) {
        List<String> command = new ArrayList<>();
        String executable = stdio.command();
        if (isWindows() && (executable.endsWith(".cmd") || executable.endsWith(".bat"))) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(executable);
        command.addAll(stdio.args());
        return command;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 读线程投递的消息；failure 非空表示连接已不可用。
     *
     * @param node 响应
     * @param failure 失败原因
     */
    private record Message(JsonNode node, String failure) {
    }
}
