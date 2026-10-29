package com.piagent.application.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可推进的测试时钟。
 */
public final class MutableClock extends Clock {

    private Instant now;

    /**
     * 创建时钟。
     *
     * @param now 初始时间
     */
    public MutableClock(Instant now) {
        this.now = now;
    }

    /**
     * 前进指定时长。
     *
     * @param amount 时长
     */
    public void advance(java.time.Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
