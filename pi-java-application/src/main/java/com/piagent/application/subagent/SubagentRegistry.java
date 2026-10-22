package com.piagent.application.subagent;

import com.piagent.domain.subagent.SubagentToolset;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.time.Duration;

/**
 * {@code subagent} 工具的定义。
 *
 * <p>它不从属于 {@code BuiltInToolCatalog}：内置目录在基础设施层，而子代理依赖应用层的
 * Agent Loop。把定义放在这里，可以保证“目录项”与“执行者”始终成对出现，
 * 不会出现目录里有工具却没有实现的挂空情况。</p>
 *
 * <p>风险级别 {@link ToolRisk#MEDIUM}：子代理会消耗额度并可能间接触发写操作，
 * 但它本身不直接改文件，写操作仍由子代理内部的工具各自走审批。</p>
 */
public final class SubagentRegistry {

    /** 工具名。 */
    public static final String SUBAGENT_TOOL = SubagentToolset.SUBAGENT_TOOL;

    /**
     * 工具级超时（秒）。
     *
     * <p>受 {@code ToolDescriptor} 的上限约束（10 分钟）：工具调度器到点会用
     * {@code Future.cancel(true)} 中断调用线程，因此子代理最长可运行时长
     * 不能超过这个值。{@code SubagentPolicy} 的最大超时默认 900 秒，
     * 实际生效值会被这里截断。</p>
     */
    private static final int TIMEOUT_SECONDS = 600;

    private SubagentRegistry() {
    }

    /**
     * 工具定义。
     *
     * @return 定义
     */
    public static ToolDescriptor descriptor() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "mode": {
                      "type": "string",
                      "enum": ["SPAWN", "FORK"],
                      "description": "SPAWN starts a fresh child session; FORK copies the current context."
                    },
                    "task": {
                      "type": "string",
                      "description": "Self-contained description of what the subagent must do."
                    },
                    "persona": {
                      "type": "string",
                      "description": "Optional role instruction, e.g. 'a meticulous code reviewer'."
                    },
                    "tools": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "Optional allow-list of tool names; the subagent only gets the intersection with yours."
                    },
                    "timeoutSeconds": {
                      "type": "integer",
                      "minimum": 10,
                      "description": "Optional timeout; the subagent is cancelled when it expires."
                    }
                  },
                  "required": ["task"],
                  "additionalProperties": false
                }
                """;
        return new ToolDescriptor(new ToolName(SUBAGENT_TOOL),
                "Delegate a self-contained task to a subagent and return its conclusion. "
                        + "Use it for isolated research or review so your own context stays small.",
                schema, ToolRisk.MEDIUM, ExecutionMode.SEQUENTIAL, Duration.ofSeconds(TIMEOUT_SECONDS));
    }
}
