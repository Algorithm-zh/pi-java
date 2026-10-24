package com.piagent.trigger.cli;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 工具目录命令组。
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "tools", mixinStandardHelpOptions = true, description = "查看工具目录与策略效果。",
        subcommands = ToolsListCommand.class)
public final class ToolsCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        CommandLine commandLine = spec.commandLine();
        commandLine.usage(commandLine.getOut());
    }
}
