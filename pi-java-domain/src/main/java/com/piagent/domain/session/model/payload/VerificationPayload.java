package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryType;

/**
 * 一次自动验证命令的执行结果。
 *
 * @param runId 产生结果的 run
 * @param command 执行的命令
 * @param passed 是否通过（退出码 0 且未超时）
 * @param exitCode 退出码；超时或无法启动时为 null
 * @param timedOut 是否超时
 * @param truncated 输出是否被截断
 * @param durationMs 耗时毫秒
 * @param output 合并后的输出（可能截断）
 */
public record VerificationPayload(
        String runId,
        String command,
        boolean passed,
        Integer exitCode,
        boolean timedOut,
        boolean truncated,
        long durationMs,
        String output) implements EntryPayload {

    /**
     * 规范化可空字段。
     *
     * @param runId run
     * @param command 命令
     * @param passed 是否通过
     * @param exitCode 退出码
     * @param timedOut 超时
     * @param truncated 截断
     * @param durationMs 耗时
     * @param output 输出
     */
    public VerificationPayload {
        runId = runId == null ? "" : runId;
        command = command == null ? "" : command;
        output = output == null ? "" : output;
        durationMs = Math.max(0, durationMs);
    }

    @Override
    public EntryType entryType() {
        return EntryType.VERIFICATION;
    }
}
