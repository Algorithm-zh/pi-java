package com.piagent.application.identity.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败节流。
 *
 * <p>按“租户短名 + 登录名”计数，15 分钟内失败 5 次锁定 5 分钟。计数只保存在
 * 进程内，重启后清零；锁定只影响对应账号，不阻断其他用户。</p>
 */
final class LoginThrottle {

    private static final int MAX_FAILURES = 5;
    private static final int MAX_TRACKED_ACCOUNTS = 10_000;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    /**
     * 账号是否处于锁定期。
     *
     * @param key 账号键
     * @param now 当前时间
     * @return true 表示仍在锁定中
     */
    boolean isLocked(String key, Instant now) {
        Attempts attempts = failures.get(key);
        return attempts != null && attempts.lockedUntil() != null
                && now.isBefore(attempts.lockedUntil());
    }

    /**
     * 记录一次失败；达到阈值后进入锁定期。
     *
     * @param key 账号键
     * @param now 当前时间
     */
    void recordFailure(String key, Instant now) {
        if (failures.size() > MAX_TRACKED_ACCOUNTS) {
            failures.entrySet().removeIf(entry ->
                    now.isAfter(entry.getValue().firstFailure().plus(FAILURE_WINDOW)));
        }
        failures.compute(key, (ignored, current) -> {
            if (current == null || now.isAfter(current.firstFailure().plus(FAILURE_WINDOW))) {
                return new Attempts(1, now, null);
            }
            int count = current.count() + 1;
            return new Attempts(count, current.firstFailure(),
                    count >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null);
        });
    }

    /**
     * 清除账号的失败记录。
     *
     * @param key 账号键
     */
    void reset(String key) {
        failures.remove(key);
    }

    private record Attempts(int count, Instant firstFailure, Instant lockedUntil) {
    }
}
