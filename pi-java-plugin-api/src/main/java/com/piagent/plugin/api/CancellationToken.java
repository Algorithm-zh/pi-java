package com.piagent.plugin.api;

/**
 * 插件取消令牌。
 *
 * <p>插件应在长循环中周期性检查；令牌为 true 时应尽快返回错误结果或抛出异常。</p>
 */
public interface CancellationToken {

    /**
     * 是否已取消。
     *
     * @return true 表示应停止工作
     */
    boolean isCancelled();
}
