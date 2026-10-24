package com.piagent.trigger.cli;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * 展示单个会话的头信息与当前分支全部 entry。
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "show", mixinStandardHelpOptions = true, description = "展示会话与当前分支。")
public final class SessionsShowCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "ID", description = "会话 ID。")
    private String sessionId;

    @Spec
    private CommandSpec spec;

    private final CliSessionService sessionService;

    /**
     * 创建展示命令。
     *
     * @param sessionService CLI 会话辅助
     */
    public SessionsShowCommand(CliSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public Integer call() {
        CliTerminal terminal = CliTerminal.of(spec.commandLine());
        ChatSession session = sessionService.require(sessionId);
        List<SessionEntry> entries = sessionService.branch(session.identity().id());
        terminal.println("会话: " + session.identity().id() + " · " + session.title());
        terminal.println("状态: " + session.status() + " · 模型 " + session.config().modelProfileId()
                + " · 思考 " + session.config().thinkingLevel());
        terminal.println("目录: " + session.config().cwd() + " · " + entries.size() + " 条 entry");
        for (SessionEntry entry : entries) {
            terminal.println("#" + entry.seq() + "  " + CliProjection.summary(entry));
        }
        return 0;
    }
}
