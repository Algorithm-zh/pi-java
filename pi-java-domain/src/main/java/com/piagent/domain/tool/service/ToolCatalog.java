package com.piagent.domain.tool.service;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 不可变工具目录。
 *
 * <p>目录项没有生命周期，创建后整体替换；同一目录内工具名必须唯一。
 * 插件卸载时构造新目录并整体替换，运行中的 run 持有旧目录快照。</p>
 */
public final class ToolCatalog {

    private final Map<ToolName, ToolDescriptor> descriptors;

    /**
     * 创建目录。
     *
     * @param descriptors 工具定义
     */
    public ToolCatalog(List<ToolDescriptor> descriptors) {
        Map<ToolName, ToolDescriptor> index = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            ToolDescriptor previous = index.putIfAbsent(descriptor.name(), descriptor);
            if (previous != null) {
                throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                        "工具名重复: " + descriptor.name());
            }
        }
        this.descriptors = Map.copyOf(index);
    }

    /**
     * 全部工具定义。
     *
     * @return 不可变列表
     */
    public List<ToolDescriptor> descriptors() {
        return List.copyOf(descriptors.values());
    }

    /**
     * 查找工具。
     *
     * @param name 工具名
     * @return 工具定义
     */
    public Optional<ToolDescriptor> find(ToolName name) {
        return Optional.ofNullable(descriptors.get(name));
    }

    /**
     * 要求工具存在。
     *
     * @param name 工具名
     * @return 工具定义
     */
    public ToolDescriptor require(ToolName name) {
        return find(name).orElseThrow(() -> new DomainException(
                DomainErrorCode.TOOL_NOT_FOUND, "工具不存在: " + name));
    }
}
