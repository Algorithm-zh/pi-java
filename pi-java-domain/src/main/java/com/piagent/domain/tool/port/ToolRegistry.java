package com.piagent.domain.tool.port;

import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import java.util.List;
import java.util.Optional;

/**
 * 工具目录查询端口。
 */
public interface ToolRegistry {

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
