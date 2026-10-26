package com.piagent.domain.verify.model;

/**
 * 验证命令的原始执行结果。
 *
 * @param command 命令
 * @param exitCode 退出码；无法启动或超时为空
 * @param timedOut 是否超时
 * @param truncated 输出是否被截断
 * @param durationMs 耗时毫秒
 * @param output 合并后的输出（已按上限截断）
 */
public record VerificationOutcome(
        String command,
        Integer exitCode,
        boolean timedOut,
        boolean truncated,
        long durationMs,
        String output) {

    /**
     * 规范化可空字段。
     *
     * @param command 命令
     * @param exitCode 退出码
     * @param timedOut 超时
     * @param truncated 截断
     * @param durationMs 耗时
     * @param output 输出
     */
    public VerificationOutcome {
        command = command == null ? "" : command;
        output = output == null ? "" : output;
        durationMs = Math.max(0, durationMs);
    }

    /**
     * 是否通过。
     *
     * @return true 表示退出码为 0 且未超时
     */
    public boolean passed() {
        return !timedOut && exitCode != null && exitCode == 0;
    }
}
