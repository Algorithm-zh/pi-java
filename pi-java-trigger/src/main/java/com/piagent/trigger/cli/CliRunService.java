package com.piagent.trigger.cli;

import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.agent.service.RunEventHub;
import com.piagent.application.agent.service.RunSubscription;
import com.piagent.application.governance.service.ApprovalApplicationService;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.session.model.SessionId;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.stereotype.Component;

/**
 * 启动 run 并建立 CLI 事件订阅。
 *
 * <p>与 SSE 使用同一个 {@link RunEventHub}：从 seq 0 开始订阅，run 启动早期
 * 产生的事件会先重放，不会因为 CLI 启动稍慢而丢失。</p>
 */
@Component
public class CliRunService {

    private final AgentLoopService loops;
    private final RunEventHub hub;
    private final ApprovalApplicationService approvals;

    /**
     * 创建 CLI 运行服务。
     *
     * @param loops Agent Loop 用例
     * @param hub 事件中心
     * @param approvals 审批用例
     */
    public CliRunService(AgentLoopService loops, RunEventHub hub,
            ApprovalApplicationService approvals) {
        this.loops = loops;
        this.hub = hub;
        this.approvals = approvals;
    }

    /**
     * 启动一次 run 并订阅全部事件。
     *
     * @param sessionId 会话标识
     * @param text 用户文本
     * @return 可轮询的 CLI run
     */
    public CliRun start(SessionId sessionId, String text) {
        RunHandle handle = loops.start(new SendMessageCommand(sessionId, text));
        LinkedBlockingQueue<RunEvent> events = new LinkedBlockingQueue<>();
        RunSubscription subscription = hub.subscribe(handle.runId(), 0, events::add);
        return new CliRun(handle, events, subscription, loops, approvals);
    }
}
