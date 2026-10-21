package com.piagent.infrastructure.tool;

import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.port.ToolRegistry;
import com.piagent.domain.tool.service.ToolCatalog;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 基于不可变目录的工具注册表。
 *
 * <p>插件装配时会构造新实例并整体替换 Bean，而不是原地修改目录；
 * 运行中的 run 持有旧快照，卸载插件不会影响正在执行的任务。</p>
 */
public final class CatalogToolRegistry implements ToolRegistry {

    private final ToolCatalog catalog;

    /**
     * 创建注册表。
     *
     * @param descriptors 工具定义
     */
    public CatalogToolRegistry(Collection<ToolDescriptor> descriptors) {
        this.catalog = new ToolCatalog(List.copyOf(descriptors));
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return catalog.descriptors();
    }

    @Override
    public Optional<ToolDescriptor> find(ToolName name) {
        return catalog.find(name);
    }

    @Override
    public ToolDescriptor require(ToolName name) {
        return catalog.require(name);
    }
}
