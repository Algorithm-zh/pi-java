package com.piagent.trigger.cli;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 单次 CLI run 的展示与交互选项。
 *
 * @param json 是否只输出最终 JSON
 * @param autoApprove 是否自动批准全部审批
 * @param timeout 等待 run 结束的最长时间
 * @param onIdle 事件间隙回调，可为 null；chat 用它响应运行中的 /abort
 */
public record RunOptions(boolean json, boolean autoApprove, Duration timeout,
        Consumer<CliRun> onIdle) {

    /**
     * 校验超时。
     *
     * @param json 是否 JSON 输出
     * @param autoApprove 是否自动批准
     * @param timeout 等待时长
     * @param onIdle 空闲回调
     */
    public RunOptions {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
    }
}
