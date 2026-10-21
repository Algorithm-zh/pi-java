package com.piagent.infrastructure.llm.openai;

import com.piagent.domain.model.llm.DoneDelta;
import com.piagent.domain.model.llm.LlmDelta;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.LlmStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 基于 JDK HttpClient 的 SSE 事件流。
 *
 * <p>连接是惰性的：首次 {@link #hasNext()} 才发送请求。429/5xx/网络错误在尚未
 * 输出任何 delta 前最多重试两次；一旦开始输出，任何错误都直接失败，避免重复
 * 文本或重复工具调用。取消会关闭响应体，使阻塞读尽快返回。</p>
 */
final class OpenAiEventStream implements LlmStream {

    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BASE_MILLIS = 150;

    private final OpenAiCall call;
    private final OpenAiSseParser parser;
    private final long deadlineNanos;
    private final Deque<LlmDelta> pending = new ArrayDeque<>();

    private volatile InputStream body;
    private volatile BufferedReader reader;
    private volatile boolean cancelled;
    private volatile boolean closed;

    private boolean started;
    private boolean ended;
    private LlmResponse response;

    OpenAiEventStream(OpenAiCall call) {
        this.call = call;
        this.parser = new OpenAiSseParser(call.mapper());
        this.deadlineNanos = System.nanoTime() + call.context().timeout().toNanos();
    }

    @Override
    public boolean hasNext() {
        if (cancelled || closed) {
            return false;
        }
        if (!pending.isEmpty()) {
            return true;
        }
        if (ended) {
            return false;
        }
        try {
            if (!started) {
                openWithRetry();
            }
            while (pending.isEmpty() && !ended) {
                if (cancelled || closed) {
                    return false;
                }
                readMore();
            }
        } catch (LlmGatewayException error) {
            closeQuietly();
            throw error;
        } catch (IOException error) {
            closeQuietly();
            if (cancelled || closed) {
                return false;
            }
            throw new LlmGatewayException(LlmErrorCode.NETWORK, "读取 LLM 流失败", error);
        }
        return !pending.isEmpty();
    }

    @Override
    public LlmDelta next() {
        if (!hasNext()) {
            throw new NoSuchElementException("LLM 流已结束");
        }
        return pending.poll();
    }

    @Override
    public Optional<LlmResponse> response() {
        return Optional.ofNullable(response);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
        closeQuietly();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly();
    }

    private void openWithRetry() {
        int attempt = 0;
        while (true) {
            checkDeadline();
            if (cancelled || closed) {
                return;
            }
            try {
                HttpResponse<InputStream> httpResponse = call.client().send(call.request(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                    body = httpResponse.body();
                    reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                    started = true;
                    return;
                }
                String errorBody = readErrorBody(httpResponse.body());
                LlmGatewayException failure = call.errorMapper().map(httpResponse.statusCode(), errorBody);
                if (failure.retryable() && attempt < MAX_RETRIES) {
                    attempt++;
                    sleepBeforeRetry(attempt);
                    continue;
                }
                throw failure;
            } catch (HttpTimeoutException error) {
                LlmGatewayException failure = new LlmGatewayException(LlmErrorCode.TIMEOUT,
                        "LLM 请求超时", error);
                if (attempt >= MAX_RETRIES) {
                    throw failure;
                }
                attempt++;
                sleepBeforeRetry(attempt);
            } catch (IOException error) {
                LlmGatewayException failure = new LlmGatewayException(LlmErrorCode.NETWORK,
                        "连接 LLM 失败", error);
                if (attempt >= MAX_RETRIES) {
                    throw failure;
                }
                attempt++;
                sleepBeforeRetry(attempt);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                cancelled = true;
                throw new LlmGatewayException(LlmErrorCode.CANCELLED, "LLM 调用被中断", error);
            }
        }
    }

    private void readMore() throws IOException {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            checkDeadline();
            if (line.isEmpty()) {
                if (data.length() > 0 && dispatch(data.toString())) {
                    return;
                }
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
            }
        }
        if (data.length() > 0 && dispatch(data.toString())) {
            return;
        }
        finishStream();
    }

    private boolean dispatch(String data) {
        if ("[DONE]".equals(data.trim())) {
            finishStream();
            return true;
        }
        pending.addAll(parser.accept(data));
        return !pending.isEmpty();
    }

    private void finishStream() {
        if (ended) {
            return;
        }
        response = parser.finish();
        pending.add(new DoneDelta(response.stopReason()));
        ended = true;
        closeQuietly();
    }

    private void checkDeadline() {
        if (System.nanoTime() - deadlineNanos > 0) {
            throw new LlmGatewayException(LlmErrorCode.TIMEOUT, "LLM 调用超过总超时");
        }
    }

    private void sleepBeforeRetry(int attempt) {
        long remaining = RETRY_BASE_MILLIS * attempt;
        while (remaining > 0) {
            if (cancelled || closed) {
                return;
            }
            long chunk = Math.min(remaining, 50);
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                cancelled = true;
                throw new LlmGatewayException(LlmErrorCode.CANCELLED, "LLM 重试等待被中断", error);
            }
            remaining -= chunk;
        }
    }

    private String readErrorBody(InputStream errorBody) {
        try (InputStream input = errorBody) {
            return new String(input.readNBytes(8192), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }

    private void closeQuietly() {
        BufferedReader currentReader = reader;
        if (currentReader != null) {
            try {
                currentReader.close();
            } catch (IOException ignored) {
                // 关闭失败不影响调用结果。
            }
        }
        InputStream currentBody = body;
        if (currentBody != null) {
            try {
                currentBody.close();
            } catch (IOException ignored) {
                // 关闭失败不影响调用结果。
            }
        }
    }
}
