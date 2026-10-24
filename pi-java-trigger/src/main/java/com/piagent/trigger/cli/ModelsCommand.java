package com.piagent.trigger.cli;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 模型档案命令组。
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "models", mixinStandardHelpOptions = true, description = "查看模型档案。",
        subcommands = ModelsListCommand.class)
public final class ModelsCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        CommandLine commandLine = spec.commandLine();
        commandLine.usage(commandLine.getOut());
    }
}
