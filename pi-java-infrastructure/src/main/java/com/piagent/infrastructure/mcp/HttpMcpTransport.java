package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.infrastructure.config.McpSettings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Streamable HTTP 传输。
 *
 * <p>每个 JSON-RPC 请求走一次 POST；响应既可能是 {@code application/json}，
 * 也可能是包含一条或多条 {@code data:} 事件的 {@code text/event-stream}。
 * 服务端返回的 {@code Mcp-Session-Id} 会附加到后续请求，关闭时发送 DELETE。</p>
 *
 * <p>HTTP 是无状态传输，进程内没有“断线”可感知；会话过期导致的失败会直接
 * 返回工具错误，不自动重试，避免重复执行有副作用的远程工具。</p>
 */
public final class HttpMcpTransport implements McpTransport {

    private static final System.Logger LOG =
            System.getLogger(HttpMcpTransport.class.getName());
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final int ERROR_BODY_LIMIT = 500;

    private final String serverName;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI uri;
    private final Map<String, String> headers;
    private volatile String sessionId;
    private volatile boolean closed;

    /**
     * 创建传输。
     *
     * @param server 服务器配置
     * @param mapper JSON 映射器
     */
    public HttpMcpTransport(McpSettings.Server server, ObjectMapper mapper) {
        McpSettings.Server config = Objects.requireNonNull(server, "server 不能为空");
        this.serverName = config.name();
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
        this.uri = URI.create(config.http().url());
        this.headers = config.http().headers();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public JsonNode request(int id, String method, JsonNode params, Duration timeout) {
        ObjectNode payload = McpProtocol.request(mapper, id, method, params);
        HttpResponse<InputStream> response = post(payload, timeout);
        try (InputStream body = response.body()) {
            requireSuccess(response, body);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                return readEvent(body, id);
            }
            return readJson(body);
        } catch (IOException error) {
            throw new McpException("MCP_TRANSPORT",
                    "读取 MCP 响应失败: " + serverName + ": " + error.getMessage(), error);
        }
    }

    @Override
    public void notify(String method, JsonNode params) {
        ObjectNode payload = McpProtocol.notification(mapper, method, params);
        HttpResponse<InputStream> response = post(payload, Duration.ofSeconds(30));
        try (InputStream body = response.body()) {
            if (response.statusCode() >= 400) {
                throw new McpException("MCP_TRANSPORT",
                        "MCP 通知失败 " + method + " (HTTP " + response.statusCode() + ")");
            }
        } catch (IOException error) {
            LOG.log(System.Logger.Level.DEBUG, "忽略 MCP 通知响应关闭失败: " + error.getMessage());
        }
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        String session = sessionId;
        if (session == null) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header(SESSION_HEADER, session)
                    .DELETE()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException error) {
            LOG.log(System.Logger.Level.DEBUG, "关闭 MCP 会话失败: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpResponse<InputStream> post(ObjectNode payload, Duration timeout) {
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (IOException error) {
            throw new McpException("MCP_PROTOCOL", "无法序列化 MCP 请求", error);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach((key, value) -> {
            if (!isManagedHeader(key)) {
                builder.header(key, value);
            }
        });
        String session = sessionId;
        if (session != null) {
            builder.header(SESSION_HEADER, session);
        }
        try {
            HttpResponse<InputStream> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            response.headers().firstValue(SESSION_HEADER).ifPresent(value -> sessionId = value);
            return response;
        } catch (IOException error) {
            throw new McpException("MCP_TRANSPORT",
                    "MCP HTTP 请求失败: " + serverName + ": " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP_CANCELLED", "MCP HTTP 请求被取消", error);
        }
    }

    private void requireSuccess(HttpResponse<InputStream> response, InputStream body)
            throws IOException {
        int status = response.statusCode();
        if (status < 400) {
            return;
        }
        String detail = new String(body.readNBytes(ERROR_BODY_LIMIT), StandardCharsets.UTF_8);
        throw new McpException("MCP_TRANSPORT",
                "MCP HTTP 请求失败 (HTTP " + status + "): " + detail);
    }

    private JsonNode readJson(InputStream body) throws IOException {
        JsonNode node = mapper.readTree(body);
        if (node == null) {
            throw new McpException("MCP_PROTOCOL", "MCP 响应为空: " + serverName);
        }
        return node;
    }

    private JsonNode readEvent(InputStream body, int id) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    JsonNode matched = matchEvent(data, id);
                    if (matched != null) {
                        return matched;
                    }
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            JsonNode matched = matchEvent(data, id);
            if (matched != null) {
                return matched;
            }
        }
        throw new McpException("MCP_STREAM_CLOSED",
                "MCP 事件流在响应前结束: " + serverName);
    }

    private JsonNode matchEvent(StringBuilder data, int id) throws IOException {
        if (data.length() == 0) {
            return null;
        }
        JsonNode node = mapper.readTree(data.toString());
        if (node != null && node.hasNonNull("id") && node.path("id").asInt(-1) == id) {
            return node;
        }
        return null;
    }

    private static boolean isManagedHeader(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("content-type") || normalized.equals("accept")
                || normalized.equals("mcp-session-id");
    }
}
