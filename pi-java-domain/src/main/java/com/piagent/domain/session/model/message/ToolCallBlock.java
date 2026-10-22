package com.piagent.domain.session.model.message;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolName;
import java.util.Map;
import java.util.Objects;

/**
 * 模型请求执行工具的内容块。
 *
 * <p>ID 与名称在写入会话日志前必须满足工具领域约束：后续工具结果按这两个值
 * 与调用配对，非法值会让分支投影无法继续。</p>
 *
 * @param id 工具调用 ID，在一条 assistant 消息内唯一
 * @param name 工具名
 * @param arguments 工具参数，只允许 JSON 可序列化值
 */
public record ToolCallBlock(String id, String name, Map<String, Object> arguments) implements ContentBlock {

    /**
     * 校验工具调用并冻结参数。
     *
     * @param id 调用 ID
     * @param name 工具名
     * @param arguments 参数
     */
    public ToolCallBlock {
        Guard.notBlank(id, DomainErrorCode.INVALID_ARGUMENT, "工具调用 ID 不能为空");
        Guard.notBlank(name, DomainErrorCode.INVALID_ARGUMENT, "工具名不能为空");
        new ToolCallId(id);
        new ToolName(name);
        Objects.requireNonNull(arguments, "工具参数不能为空");
        arguments = Map.copyOf(arguments);
    }
}
