package com.piagent.trigger.cli;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 查看会话的只读命令组。
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "sessions", mixinStandardHelpOptions = true, description = "查看会话列表与分支内容。",
        subcommands = {SessionsListCommand.class, SessionsShowCommand.class})
public final class SessionsCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        CommandLine commandLine = spec.commandLine();
        commandLine.usage(commandLine.getOut());
    }
}
