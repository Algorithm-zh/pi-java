package com.piagent.domain.governance.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 审批展示用的脱敏参数与完整性摘要。
 *
 * <p>{@code digest} 是原始参数的规范 JSON 的 SHA-256，用于让用户界面展示的
 * 内容与真正执行的参数可校验；{@code redacted} 是去掉密钥后的参数副本。
 * 两者由应用层计算，领域层只校验形状并冻结引用。</p>
 *
 * @param digest 64 位小写十六进制
 * @param redacted 脱敏参数
 */
public record ApprovalArguments(String digest, Map<String, Object> redacted) {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    /**
     * 校验参数摘要。
     *
     * @param digest 摘要
     * @param redacted 脱敏参数
     */
    public ApprovalArguments {
        Objects.requireNonNull(digest, "digest 不能为空");
        Guard.require(HEX_64.matcher(digest).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "参数摘要必须是 64 位小写十六进制");
        redacted = redacted == null ? Map.of() : Map.copyOf(redacted);
    }
}
