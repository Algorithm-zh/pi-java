package com.piagent.trigger.cli;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * CLI 根命令。
 *
 * <p>不带子命令时打印用法；实际启动模式由 {@link CliMode} 在进入 Spring 前判定，
 * 因此 Web 模式不会创建本命令。</p>
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "pi", mixinStandardHelpOptions = true, version = "pi-java 0.2.0",
        description = "pi-java 编码代理 CLI。Web 与 CLI 共用同一套会话、模型与工具体系。",
        subcommands = {RunCommand.class, ChatCommand.class, SessionsCommand.class,
                ModelsCommand.class, ToolsCommand.class})
public final class PiRootCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        CommandLine commandLine = spec.commandLine();
        commandLine.usage(commandLine.getOut());
    }
}
