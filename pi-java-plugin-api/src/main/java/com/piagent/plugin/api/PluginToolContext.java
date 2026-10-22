package com.piagent.plugin.api;

import java.nio.file.Path;

/**
 * 插件工具执行上下文。
 *
 * <p>工作区来自当前会话冻结的沙箱策略，插件必须以它为根解析相对路径；
 * 宿主不假设插件会遵守，但文档要求插件不访问未经用户同意的路径。</p>
 */
public interface PluginToolContext {

    /**
     * 当前工作区根目录（绝对、规范化）。
     *
     * @return 工作区
     */
    Path workspaceRoot();

    /**
     * 取消令牌。
     *
     * @return 令牌
     */
    CancellationToken cancellation();

    /**
     * 插件日志器。
     *
     * @return 日志器
     */
    PluginLogger log();

    /**
     * 上报部分输出。
     *
     * <p>M5 仅保留契约并丢弃内容；工具事件流在后续里程碑接入。</p>
     *
     * @param partialOutput 部分输出
     */
    void progress(String partialOutput);
}
