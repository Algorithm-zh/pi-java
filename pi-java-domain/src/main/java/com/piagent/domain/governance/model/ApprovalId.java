package com.piagent.domain.governance.model;

import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.util.Objects;

/**
 * 审批请求标识。
 *
 * <p>审批待办会通过 HTTP 与 SSE 暴露给前端，因此使用与 run 相同的 ULID，
 * 保证在进程重启、日志与浏览器重连后仍可稳定引用。</p>
 *
 * @param value ULID
 */
public record ApprovalId(Ulid value) {

    /**
     * 校验审批标识。
     *
     * @param value ULID
     */
    public ApprovalId {
        Objects.requireNonNull(value, "approvalId 不能为空");
    }

    /**
     * 解析字符串形式的审批标识。
     *
     * @param raw 26 位 ULID
     * @return 审批标识
     */
    public static ApprovalId parse(String raw) {
        return new ApprovalId(Ulid.parse(raw));
    }

    /**
     * 通过生成器创建新审批标识。
     *
     * @param generator 标识生成器
     * @return 新审批标识
     */
    public static ApprovalId newId(IdGenerator generator) {
        return new ApprovalId(Ulid.parse(generator.newId()));
    }

    @Override
    public String toString() {
        return value.value();
    }
}
