package com.piagent.domain.governance.service;

import com.piagent.domain.governance.model.ApprovalArguments;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具参数脱敏与摘要生成器。
 *
 * <p>审批界面只展示脱敏后的参数，而审批记录需要一个能稳定识别“同一组参数”的
 * 摘要。这里用纯 JDK 实现 canonical JSON：对象键按字典序排列、数字去掉无意义
 * 尾零、字符串按 JSON 规则转义，再对 UTF-8 字节做 SHA-256。相同语义的参数无论
 * 键顺序如何都会得到相同摘要。</p>
 *
 * <p>脱敏只作用于展示副本：键名包含 key/token/secret/password/authorization/
 * credential 的字段值统一替换为 {@value #MASK}。null 在展示副本中替换为
 * {@value #NULL_MARKER}，因为领域值对象使用 {@code Map.copyOf} 冻结，不接受
 * null 值。摘要仍基于原始参数，null 语义不会丢失。</p>
 */
public final class ApprovalArgumentsSanitizer {

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(key|token|secret|password|authorization|credential).*");
    private static final String MASK = "***";
    private static final String NULL_MARKER = "<null>";

    private ApprovalArgumentsSanitizer() {
    }

    /**
     * 生成审批参数摘要与脱敏副本。
     *
     * @param arguments 原始工具参数
     * @return 不可变的审批参数
     */
    public static ApprovalArguments sanitize(Map<String, Object> arguments) {
        Map<String, Object> source = arguments == null ? Map.of() : arguments;
        return new ApprovalArguments(sha256(canonicalJson(source)), redact(source));
    }

    /**
     * 只生成脱敏副本，供 SSE 工具事件使用。
     *
     * @param arguments 原始工具参数
     * @return 不可变脱敏参数
     */
    public static Map<String, Object> redact(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            String name = String.valueOf(key);
            result.put(name, isSensitive(name) ? MASK : redactValue(value));
        });
        return Map.copyOf(result);
    }

    private static Object redactValue(Object value) {
        if (value == null) {
            return NULL_MARKER;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                nested.put(name, isSensitive(name) ? MASK : redactValue(item));
            });
            return Map.copyOf(nested);
        }
        if (value instanceof Iterable<?> items) {
            List<Object> copied = new ArrayList<>();
            items.forEach(item -> copied.add(redactValue(item)));
            return List.copyOf(copied);
        }
        if (value.getClass().isArray()) {
            List<Object> copied = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                copied.add(redactValue(Array.get(value, index)));
            }
            return List.copyOf(copied);
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        return SENSITIVE_KEY.matcher(key).matches();
    }

    private static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        writeCanonical(value, out);
        return out.toString();
    }

    private static void writeCanonical(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(text, out);
        } else if (value instanceof Boolean || value instanceof BigInteger
                || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            out.append(value);
        } else if (value instanceof BigDecimal decimal) {
            out.append(decimal.stripTrailingZeros().toPlainString());
        } else if (value instanceof Float number) {
            writeDecimal(number.doubleValue(), out);
        } else if (value instanceof Double number) {
            writeDecimal(number, out);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out);
        } else if (value instanceof Iterable<?> items) {
            writeArray(items, out);
        } else if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                items.add(Array.get(value, index));
            }
            writeArray(items, out);
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeDecimal(double number, StringBuilder out) {
        if (!Double.isFinite(number)) {
            writeString(String.valueOf(number), out);
            return;
        }
        out.append(BigDecimal.valueOf(number).stripTrailingZeros().toPlainString());
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        entries.sort((left, right) -> String.valueOf(left.getKey())
                .compareTo(String.valueOf(right.getKey())));
        out.append('{');
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            Map.Entry<?, ?> entry = entries.get(index);
            writeString(String.valueOf(entry.getKey()), out);
            out.append(':');
            writeCanonical(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(Iterable<?> items, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(',');
            }
            writeCanonical(item, out);
            first = false;
        }
        out.append(']');
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            switch (value) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (value < 0x20) {
                        out.append(String.format("\\u%04x", (int) value));
                    } else {
                        out.append(value);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 不支持 SHA-256", error);
        }
    }
}
