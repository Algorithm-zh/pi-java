package com.piagent.trigger.cli;

import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.session.model.ChatSession;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * 列出最近会话。
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "list", mixinStandardHelpOptions = true, description = "列出最近会话。")
public final class SessionsListCommand implements Callable<Integer> {

    private static final String ROW = "%-26s  %-8s  %-16s  %-30s  %s";

    @Option(names = "--cwd", paramLabel = "PATH", description = "只列出该工作目录下的会话。")
    private String cwd;

    @Option(names = "--limit", paramLabel = "N", defaultValue = "50",
            description = "最大条数，默认 50。")
    private int limit;

    @Spec
    private CommandSpec spec;

    private final SessionApplicationService sessions;
    private final CliSessionService sessionService;

    /**
     * 创建列表命令。
     *
     * @param sessions 会话用例
     * @param sessionService CLI 会话辅助
     */
    public SessionsListCommand(SessionApplicationService sessions,
            CliSessionService sessionService) {
        this.sessions = sessions;
        this.sessionService = sessionService;
    }

    @Override
    public Integer call() {
        CliTerminal terminal = CliTerminal.of(spec.commandLine());
        Path filter = cwd == null || cwd.isBlank() ? null : sessionService.cwd(cwd);
        List<ChatSession> result = sessions.list(filter, limit);
        terminal.println(String.format(ROW, "ID", "STATUS", "MODEL", "TITLE", "CWD"));
        for (ChatSession session : result) {
            terminal.println(String.format(ROW,
                    session.identity().id().toString(),
                    session.status().name(),
                    CliText.oneLine(session.config().modelProfileId(), 16),
                    CliText.oneLine(session.title(), 30),
                    CliText.oneLine(session.config().cwd().toString(), 50)));
        }
        terminal.println("共 " + result.size() + " 个会话");
        return 0;
    }
}
