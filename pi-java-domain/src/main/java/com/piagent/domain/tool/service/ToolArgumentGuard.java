package com.piagent.domain.tool.service;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具参数读取与校验。
 *
 * <p>模型返回的参数是不可信 JSON；所有读取都做类型检查，未知参数默认拒绝，
 * 避免拼写错误被静默忽略。错误码统一为 {@code TOOL_INVALID_ARGUMENT}，
 * 由调用器转成 {@code isError=true} 的工具结果。</p>
 */
public final class ToolArgumentGuard {

    private ToolArgumentGuard() {
    }

    /**
     * 拒绝未声明的参数。
     *
     * @param arguments 参数
     * @param allowed 允许的参数名
     */
    public static void rejectUnknown(Map<String, Object> arguments, Set<String> allowed) {
        List<String> unknown = new ArrayList<>();
        for (String key : arguments.keySet()) {
            if (!allowed.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            unknown.sort(String::compareTo);
            throw invalid("未知参数: " + String.join(", ", unknown));
        }
    }

    /**
     * 读取必填字符串。
     *
     * @param arguments 参数
     * @param key 参数名
     * @param maxLength 最大长度
     * @return 字符串
     */
    public static String requireString(Map<String, Object> arguments, String key, int maxLength) {
        Object value = arguments.get(key);
        if (!(value instanceof String text)) {
            throw invalid("参数 " + key + " 必须是字符串");
        }
        if (text.isBlank()) {
            throw invalid("参数 " + key + " 不能为空");
        }
        return maxLength(text, key, maxLength);
    }

    /**
     * 读取可选字符串。
     *
     * @param arguments 参数
     * @param key 参数名
     * @param defaultValue 缺省值
     * @param maxLength 最大长度
     * @return 字符串
     */
    public static String optionalString(Map<String, Object> arguments, String key,
            String defaultValue, int maxLength) {
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text)) {
            throw invalid("参数 " + key + " 必须是字符串");
        }
        return maxLength(text, key, maxLength);
    }

    /**
     * 读取可选整数。
     *
     * @param arguments 参数
     * @param key 参数名
     * @param defaultValue 缺省值
     * @param min 最小值
     * @param max 最大值
     * @return 整数
     */
    public static int optionalInt(Map<String, Object> arguments, String key, int defaultValue,
            int min, int max) {
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw invalid("参数 " + key + " 必须是整数");
        }
        long parsed = number.longValue();
        if (parsed < min || parsed > max) {
            throw invalid("参数 " + key + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return (int) parsed;
    }

    /**
     * 读取可选布尔值。
     *
     * @param arguments 参数
     * @param key 参数名
     * @param defaultValue 缺省值
     * @return 布尔值
     */
    public static boolean optionalBoolean(Map<String, Object> arguments, String key,
            boolean defaultValue) {
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean flag)) {
            throw invalid("参数 " + key + " 必须是布尔值");
        }
        return flag;
    }

    private static String maxLength(String value, String key, int maxLength) {
        Objects.requireNonNull(value, "value 不能为空");
        if (value.length() > maxLength) {
            throw invalid("参数 " + key + " 不能超过 " + maxLength + " 个字符");
        }
        return value;
    }

    private static DomainException invalid(String message) {
        return new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT, message);
    }
}
