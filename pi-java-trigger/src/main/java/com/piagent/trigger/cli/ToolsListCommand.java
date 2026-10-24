package com.piagent.trigger.cli;

import com.piagent.application.tool.readmodel.ToolView;
import com.piagent.application.tool.service.ToolCatalogApplicationService;
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
 * 列出工具目录与当前工作区的策略效果。
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "list", mixinStandardHelpOptions = true, description = "列出工具与策略效果。")
public final class ToolsListCommand implements Callable<Integer> {

    private static final String ROW = "%-18s  %-8s  %-8s  %-8s  %-12s  %s";

    @Option(names = "--cwd", paramLabel = "PATH", description = "工作目录，默认当前目录。")
    private String cwd;

    @Spec
    private CommandSpec spec;

    private final ToolCatalogApplicationService catalog;

    /**
     * 创建列表命令。
     *
     * @param catalog 工具目录用例
     */
    public ToolsListCommand(ToolCatalogApplicationService catalog) {
        this.catalog = catalog;
    }

    @Override
    public Integer call() {
        CliTerminal terminal = CliTerminal.of(spec.commandLine());
        List<ToolView> tools = catalog.list(cwd);
        terminal.println(String.format(ROW, "NAME", "RISK", "EFFECT", "APPROVAL", "MODE", "TIMEOUT"));
        for (ToolView tool : tools) {
            terminal.println(String.format(ROW,
                    CliText.oneLine(tool.name(), 18),
                    tool.risk().name(),
                    tool.effect().name(),
                    tool.requiresApproval() ? "ask" : "-",
                    tool.executionMode().name(),
                    tool.timeoutMs() + "ms"));
        }
        terminal.println("共 " + tools.size() + " 个工具");
        return 0;
    }
}
