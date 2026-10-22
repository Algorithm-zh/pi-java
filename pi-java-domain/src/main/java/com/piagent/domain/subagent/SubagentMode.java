package com.piagent.domain.subagent;

/**
 * 子代理的启动模式。
 *
 * <p>两种模式的差别只在“初始上下文”：</p>
 * <ul>
 *   <li>{@link #SPAWN}：全新子会话，零父上下文。适合“独立调研”类任务，
 *       上下文最省，也最不受父对话影响；</li>
 *   <li>{@link #FORK}：把父会话当前分支按预算裁剪后复制为子会话初始 entry。
 *       适合“需要继承当前进展”的任务，例如让子代理在已读过的文件基础上继续分析。</li>
 * </ul>
 */
public enum SubagentMode {

    /** 全新子会话，不继承父上下文。 */
    SPAWN,

    /** 复制父投影作为子会话起点。 */
    FORK
}
