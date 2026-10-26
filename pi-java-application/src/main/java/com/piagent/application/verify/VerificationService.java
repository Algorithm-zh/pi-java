package com.piagent.application.verify;

import com.piagent.application.agent.service.RunAuxEvents;
import com.piagent.application.agent.service.RunRuntime;
import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.verify.model.VerificationOutcome;
import com.piagent.domain.verify.port.VerificationRunner;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 自动验证用例：执行用户配置的命令、记录结果，并在失败时回写修复提示。
 *
 * <p>命令来自配置而非模型，因此不进入审批；但执行器仍限制超时与输出长度。
 * 结果作为 VERIFICATION entry 进入会话日志，重启后仍可展示。</p>
 */
@Service
public class VerificationService {

    private static final int MAX_MESSAGE_OUTPUT = 6_000;

    private final VerificationRunner runner;
    private final SessionEntryAppender appender;
    private final RunRuntime runtime;
    private final RunAuxEvents events;
    private final VerificationOptions options;
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param runner 命令执行端口
     * @param appender entry 追加器
     * @param runtime 运行运行时
     * @param events 辅助事件发布器
     * @param options 配置
     * @param clock 时钟
     */
    public VerificationService(VerificationRunner runner, SessionEntryAppender appender,
            RunRuntime runtime, RunAuxEvents events, VerificationOptions options, Clock clock) {
        this.runner = runner;
        this.appender = appender;
        this.runtime = runtime;
        this.events = events;
        this.options = options;
        this.clock = clock;
    }

    /**
     * 是否配置了验证命令。
     *
     * @return true 表示启用
     */
    public boolean enabled() {
        return options.enabled();
    }

    /**
     * 失败后是否允许回写一次。
     *
     * @return true 表示回写
     */
    public boolean retryOnFailure() {
        return options.retryOnFailure();
    }

    /**
     * 执行验证命令并写入结果 entry。
     *
     * @param sessionId 会话标识
     * @param workspace 工作目录
     * @return 验证结果
     */
    public VerificationPayload verify(SessionId sessionId, Path workspace) {
        String runId = runtime.findActiveBySession(sessionId)
                .map(run -> run.id().toString())
                .orElse("");
        VerificationOutcome outcome = runner.run(options.command(), workspace,
                options.timeout(), options.maxOutputChars());
        VerificationPayload payload = new VerificationPayload(runId, options.command(),
                outcome.passed(), outcome.exitCode(), outcome.timedOut(), outcome.truncated(),
                outcome.durationMs(), outcome.output());
        appender.appendMetadata(sessionId, payload);
        events.verificationCompleted(sessionId, payload);
        return payload;
    }

    /**
     * 把失败输出回写成一条用户消息，让模型在同一 run 内继续修复。
     *
     * @param sessionId 会话标识
     * @param payload 失败结果
     */
    public void appendFollowUp(SessionId sessionId, VerificationPayload payload) {
        appender.appendMessage(sessionId, new UserMessage(
                List.of(new TextBlock(followUpText(payload))), clock.instant()));
    }

    private String followUpText(VerificationPayload payload) {
        String output = payload.output().isBlank() ? "（命令没有输出）" : payload.output();
        if (output.length() > MAX_MESSAGE_OUTPUT) {
            output = output.substring(0, MAX_MESSAGE_OUTPUT) + "\n…（输出已截断）";
        }
        String status = payload.timedOut() ? "执行超时"
                : "退出码 " + (payload.exitCode() == null ? "未知" : payload.exitCode());
        return "自动验证未通过（命令：`" + payload.command() + "`，" + status + "）。\n\n"
                + "```\n" + output + "\n```\n"
                + "请先修复失败原因，再继续完成任务；不要只解释原因。";
    }
}
