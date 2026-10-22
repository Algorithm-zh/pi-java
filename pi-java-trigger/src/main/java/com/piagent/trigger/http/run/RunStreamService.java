package com.piagent.trigger.http.run;

import com.piagent.application.agent.service.RunEventHub;
import com.piagent.domain.agent.RunId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流创建与心跳调度。
 *
 * <p>心跳线程是 daemon 平台线程：只做轻量发送，不承载业务。每个连接独立
 * 心跳任务，断连或终态后取消。SSE 超时设为 0，由客户端主动断开或 run 结束。</p>
 */
@Service
public class RunStreamService implements DisposableBean {

    private final RunEventHub hub;
    private final ScheduledExecutorService scheduler;

    /**
     * 创建流服务。
     *
     * @param hub 事件中心
     */
    public RunStreamService(RunEventHub hub) {
        this.hub = hub;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pi-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 打开一个 run 的 SSE 流。
     *
     * @param runId 运行标识
     * @param afterSeq 已收到的最大事件序号；0 表示从头重放
     * @return SSE emitter
     */
    public SseEmitter open(RunId runId, long afterSeq) {
        SseEmitter emitter = new SseEmitter(0L);
        new RunSseChannel(runId, afterSeq, hub, emitter, scheduler).start();
        return emitter;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
