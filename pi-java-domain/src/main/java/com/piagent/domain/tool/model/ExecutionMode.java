package com.piagent.domain.tool.model;

/**
 * 工具执行模式。
 *
 * <p>只读工具可并行；写文件、执行命令等有副作用的工具必须串行，
 * 由 Agent Loop 的工具协调器按此字段分批。</p>
 */
public enum ExecutionMode {

    /** 可与同批工具并行执行。 */
    PARALLEL,

    /** 必须独占执行，保持模型给出的顺序。 */
    SEQUENTIAL
}
