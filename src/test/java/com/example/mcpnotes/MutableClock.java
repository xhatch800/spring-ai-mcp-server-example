package com.example.mcpnotes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that only moves when a test says so. */
final class MutableClock extends Clock {

    static final Instant START = Instant.parse("2026-01-15T10:00:00Z");

    private Instant now;

    MutableClock() {
        this(START);
    }

    MutableClock(Instant now) {
        this.now = now;
    }

    void set(Instant instant) {
        this.now = instant;
    }

    void advance(Duration duration) {
        this.now = now.plus(duration);
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
