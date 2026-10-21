package com.piagent.domain.shared;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 时间有序的 26 位 ULID。
 *
 * <p>前 10 位编码 48 位毫秒时间戳，后 16 位编码 80 位随机数。
 * 字符串字典序与生成时间顺序一致，因此可以直接作为 CHAR(26) 主键并按范围扫描。
 * 随机部分使用 {@link SecureRandom}，避免同一毫秒内被猜测或碰撞。
 */
public record Ulid(String value) implements Comparable<Ulid> {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 26;
    private static final int TIME_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 校验并规范化 ULID 字符串。
     *
     * @param value 26 位 Crockford Base32 字符串，大小写不敏感
     */
    public Ulid {
        Objects.requireNonNull(value, "ULID 不能为空");
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.length() != LENGTH) {
            throw new IllegalArgumentException("ULID 长度必须为 26: " + value);
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (indexOf(normalized.charAt(i)) < 0) {
                throw new IllegalArgumentException("ULID 含非法字符: " + value);
            }
        }
        if (indexOf(normalized.charAt(0)) > 7) {
            throw new IllegalArgumentException("ULID 时间戳溢出: " + value);
        }
        value = normalized;
    }

    /**
     * 基于指定时间生成新 ULID。
     *
     * @param instant 生成时间，不能为空
     * @return 新的 ULID
     */
    public static Ulid generate(Instant instant) {
        Objects.requireNonNull(instant, "instant 不能为空");
        long timestamp = instant.toEpochMilli();
        char[] chars = new char[LENGTH];
        for (int i = 0; i < TIME_LENGTH; i++) {
            int shift = 45 - i * 5;
            chars[i] = ALPHABET[(int) ((timestamp >>> shift) & 31)];
        }
        for (int i = TIME_LENGTH; i < LENGTH; i++) {
            chars[i] = ALPHABET[RANDOM.nextInt(32)];
        }
        return new Ulid(new String(chars));
    }

    /**
     * 解析已有 ULID。
     *
     * @param raw 26 位字符串
     * @return ULID 值对象
     */
    public static Ulid parse(String raw) {
        return new Ulid(raw);
    }

    /**
     * 解码时间戳，主要用于测试排序与调试。
     *
     * @return ULID 中编码的时间
     */
    public Instant instant() {
        long timestamp = 0;
        for (int i = 0; i < TIME_LENGTH; i++) {
            timestamp = (timestamp << 5) | indexOf(value.charAt(i));
        }
        return Instant.ofEpochMilli(timestamp);
    }

    @Override
    public int compareTo(Ulid other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static int indexOf(char character) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == character) {
                return i;
            }
        }
        return -1;
    }
}
