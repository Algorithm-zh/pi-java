package com.piagent.domain.extension.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 插件 API 版本。
 *
 * <p>宿主只加载主版本 1 的插件；次版本用于向前兼容地新增能力。清单里既接受
 * {@code "1"} 也接受 {@code "1.2"}，统一规范化为 {@code major.minor} 文本。</p>
 *
 * @param major 主版本
 * @param minor 次版本
 */
public record ApiVersion(int major, int minor) {

    /**
     * 校验版本号。
     *
     * @param major 主版本
     * @param minor 次版本
     */
    public ApiVersion {
        Guard.require(major >= 0, DomainErrorCode.INVALID_ARGUMENT, "API 主版本不能为负数");
        Guard.require(minor >= 0, DomainErrorCode.INVALID_ARGUMENT, "API 次版本不能为负数");
    }

    /**
     * 解析版本文本。
     *
     * @param raw 形如 {@code 1} 或 {@code 1.2} 的文本
     * @return 版本
     */
    public static ApiVersion parse(String raw) {
        String value = Guard.notBlank(raw, DomainErrorCode.INVALID_ARGUMENT, "API 版本不能为空");
        String[] parts = value.split("\\.", -1);
        Guard.require(parts.length <= 2, DomainErrorCode.INVALID_ARGUMENT,
                "API 版本必须是 MAJOR 或 MAJOR.MINOR: " + raw);
        int major = parsePart(parts[0], raw);
        int minor = parts.length == 2 ? parsePart(parts[1], raw) : 0;
        return new ApiVersion(major, minor);
    }

    /**
     * 规范文本。
     *
     * @return {@code major.minor}
     */
    public String text() {
        return major + "." + minor;
    }

    private static int parsePart(String part, String raw) {
        try {
            return Integer.parseInt(Objects.requireNonNull(part, "版本段不能为空"));
        } catch (NumberFormatException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "API 版本不是数字: " + raw, error);
        }
    }
}
