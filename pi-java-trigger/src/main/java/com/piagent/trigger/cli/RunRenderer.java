package com.piagent.trigger.cli;

import com.piagent.domain.agent.RunEvent;

/**
 * run 事件的展示端口。
 *
 * <p>行模式用纯文本，交互式界面用带颜色的终端渲染；两者共享同一事件循环，
 * 因此渲染器只负责展示与收集最终回答，不参与取消、审批或状态机。</p>
 */
public interface RunRenderer {

    /**
     * 渲染一条事件。
     *
     * @param event 运行事件
     */
    void render(RunEvent event);

    /**
     * 结束未闭合的文本行。
     */
    void finish();

    /**
     * 最终助手文本（只包含 text 增量）。
     *
     * @return 最终文本；没有时为 empty
     */
    String finalText();
}
