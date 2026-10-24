package com.piagent.trigger.cli;

import com.piagent.application.model.service.ModelProfileApplicationService;
import com.piagent.domain.model.ModelProfile;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 列出模型档案与密钥环境变量状态。
 *
 * <p>只展示环境变量名及是否设置，绝不读取或输出变量值。</p>
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "list", mixinStandardHelpOptions = true, description = "列出模型档案。")
public final class ModelsListCommand implements Callable<Integer> {

    private static final String ROW = "%-20s  %-8s  %-14s  %-24s  %s";

    @Spec
    private CommandSpec spec;

    private final ModelProfileApplicationService models;

    /**
     * 创建列表命令。
     *
     * @param models 模型档案用例
     */
    public ModelsListCommand(ModelProfileApplicationService models) {
        this.models = models;
    }

    @Override
    public Integer call() {
        CliTerminal terminal = CliTerminal.of(spec.commandLine());
        List<ModelProfile> profiles = models.listAll();
        terminal.println(String.format(ROW, "ID", "ENABLED", "PROVIDER", "MODEL", "KEY"));
        for (ModelProfile profile : profiles) {
            terminal.println(String.format(ROW,
                    CliText.oneLine(profile.id().value(), 20),
                    profile.enabled() ? "yes" : "no",
                    profile.endpoint().provider().name(),
                    CliText.oneLine(profile.endpoint().modelName(), 24),
                    keyState(profile)));
        }
        terminal.println("共 " + profiles.size() + " 个档案");
        return 0;
    }

    private static String keyState(ModelProfile profile) {
        if (!profile.endpoint().requiresApiKey()) {
            return "-";
        }
        String env = profile.endpoint().apiKeyEnv();
        return env + (System.getenv(env) == null ? " (未设置)" : " (已设置)");
    }
}
