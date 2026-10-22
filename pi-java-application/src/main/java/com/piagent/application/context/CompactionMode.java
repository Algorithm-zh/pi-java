package com.piagent.application.context;

/**
 * 压缩触发方式。
 *
 * <p>不同方式在度量与限制上不同：自动压缩每个 run 最多一次；溢出重试在同一次
 * 模型调用失败后立即执行一次；手动压缩不受次数限制，但要求会话空闲。</p>
 */
public enum CompactionMode {

    /** run 内自动触发（上下文接近窗口）。 */
    AUTO,

    /** provider 返回上下文溢出后的重试压缩。 */
    OVERFLOW,

    /** 用户显式请求 {@code /compact}。 */
    MANUAL
}
