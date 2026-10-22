package com.piagent.domain.extension.port;

import com.piagent.domain.extension.model.PluginPackage;
import java.nio.file.Path;

/**
 * 插件包检查端口。
 *
 * <p>读取 jar 清单、计算 SHA-256 并校验结构；实现位于基础设施层，
 * 领域层借此在安装用例中拿到可持久化的事实，而不接触 jar/JSON 库。</p>
 */
public interface PluginPackageInspector {

    /**
     * 检查插件包。
     *
     * @param jarPath jar 路径
     * @return 清单与校验结果
     */
    PluginPackage inspect(Path jarPath);
}
