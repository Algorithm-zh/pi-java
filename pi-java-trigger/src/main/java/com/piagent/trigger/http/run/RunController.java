package com.piagent.trigger.http.run;

import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.agent.service.RunEventHub;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 运行状态与事件重放控制器。
 *
 * <p>SSE 的 {@code Last-Event-ID} 使用 {@code runId:seq} 格式；重连时只回放
 * 该序号之后的事件。若事件缓冲已被淘汰，客户端会先收到 {@code replay.gap}，
 * 应重新加载会话快照。</p>
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final AgentLoopService agentLoop;
    private final RunEventHub hub;
    private final RunStreamService streams;

    /**
     * 创建控制器。
     *
     * @param agentLoop Agent Loop
     * @param hub 事件中心
     * @param streams SSE 流服务
     */
    public RunController(AgentLoopService agentLoop, RunEventHub hub, RunStreamService streams) {
        this.agentLoop = agentLoop;
        this.hub = hub;
        this.streams = streams;
    }

    /**
     * 查询运行状态。
     *
     * @param runId 运行 ID
     * @return 运行快照
     */
    @GetMapping("/{runId}")
    public RunResponse find(@PathVariable String runId) {
        return agentLoop.findRun(RunId.parse(runId))
                .map(RunResponse::from)
                .orElseThrow(() -> new DomainException(DomainErrorCode.RUN_NOT_FOUND,
                        "run 不存在或已被清理: " + runId));
    }

    /**
     * 订阅运行事件；断线重连时从 {@code Last-Event-ID} 之后回放。
     *
     * @param runId 运行 ID
     * @param lastEventId 上次收到的事件 ID
     * @return SSE 流
     */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        RunId parsed = RunId.parse(runId);
        if (!hub.exists(parsed)) {
            throw new DomainException(DomainErrorCode.RUN_NOT_FOUND,
                    "run 事件不存在或已被清理: " + runId);
        }
        return streams.open(parsed, parseSeq(lastEventId));
    }

    /**
     * 解析重放序号；支持 {@code runId:seq} 与纯数字两种形式。
     *
     * @param lastEventId 上次事件 ID
     * @return 序号；无值时返回 0
     */
    static long parseSeq(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        String raw = lastEventId;
        int separator = raw.lastIndexOf(':');
        if (separator >= 0) {
            raw = raw.substring(separator + 1);
        }
        try {
            long seq = Long.parseLong(raw.trim());
            if (seq < 0) {
                throw new NumberFormatException("负数");
            }
            return seq;
        } catch (NumberFormatException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "Last-Event-ID 非法: " + lastEventId);
        }
    }
}
