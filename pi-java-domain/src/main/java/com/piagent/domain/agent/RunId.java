package com.piagent.domain.agent;

import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.util.Objects;

/**
 * 一次 Agent Run 的标识。
 *
 * <p>Run 只存在于进程内，不落库；但 ID 会写进 RUN_SUMMARY entry 与 SSE 事件，
 * 因此仍使用 ULID，保证跨重启的日志可以稳定引用历史 run。</p>
 *
 * @param value ULID
 */
public record RunId(Ulid value) {

    /**
     * 校验运行标识。
     *
     * @param value ULID
     */
    public RunId {
        Objects.requireNonNull(value, "runId 不能为空");
    }

    /**
     * 解析字符串形式的运行标识。
     *
     * @param raw 26 位 ULID
     * @return 运行标识
     */
    public static RunId parse(String raw) {
        return new RunId(Ulid.parse(raw));
    }

    /**
     * 通过生成器创建新运行标识。
     *
     * @param generator 标识生成器
     * @return 新运行标识
     */
    public static RunId newId(IdGenerator generator) {
        return new RunId(Ulid.parse(generator.newId()));
    }

    @Override
    public String toString() {
        return value.value();
    }
}
