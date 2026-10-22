package com.piagent.domain.tool.port;

import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.service.ToolCatalog;
import java.util.List;
import java.util.Optional;

/**
 * 工具目录查询端口。
 */
public interface ToolRegistry {

    /**
     * 冻结当前工具目录。
     *
     * <p>run 在开始时持有快照，后续插件启停只影响新 run，不会改变正在运行的
     * run 看到的工具清单。默认实现复制当前定义并复用目录查找语义。</p>
     *
     * @return 不可变快照
     */
    default ToolRegistry snapshot() {
        ToolCatalog frozen = new ToolCatalog(descriptors());
        return new ToolRegistry() {
            @Override
            public List<ToolDescriptor> descriptors() {
                return frozen.descriptors();
            }

            @Override
            public Optional<ToolDescriptor> find(ToolName name) {
                return frozen.find(name);
            }

            @Override
            public ToolDescriptor require(ToolName name) {
                return frozen.require(name);
            }
        };
    }

    /**
     * 全部工具定义。
     *
     * @return 工具定义
     */
    List<ToolDescriptor> descriptors();

    /**
     * 查找工具。
     *
     * @param name 工具名
     * @return 工具定义
     */
    Optional<ToolDescriptor> find(ToolName name);

    /**
     * 要求工具存在。
     *
     * @param name 工具名
     * @return 工具定义
     */
    ToolDescriptor require(ToolName name);
}
