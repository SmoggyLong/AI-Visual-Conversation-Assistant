package com.aivca.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器 —— 连续超过阈值次失败后，跳闸一段时间直接拒绝请求。
 *
 * 三态: CLOSED(正常) → OPEN(熔断) → HALF_OPEN(探测) → CLOSED/HALF_OPEN
 */
@Slf4j
public class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final Duration recoveryTimeout;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant openedAt;

    public CircuitBreaker(String name, int failureThreshold, Duration recoveryTimeout) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.recoveryTimeout = recoveryTimeout;
    }

    /** 是否允许通过 */
    public synchronized boolean allowRequest() {
        State s = state.get();
        return switch (s) {
            case CLOSED -> true;
            case OPEN -> {
                if (Duration.between(openedAt, Instant.now()).compareTo(recoveryTimeout) > 0) {
                    state.set(State.HALF_OPEN);
                    log.info("[BREAKER] {} → HALF_OPEN (探测)", name);
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> true;
        };
    }

    /** 记录成功 */
    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        State old = state.getAndSet(State.CLOSED);
        if (old != State.CLOSED) {
            log.info("[BREAKER] {} → CLOSED (已恢复)", name);
        }
    }

    /** 记录失败 */
    public synchronized void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && state.get() == State.CLOSED) {
            state.set(State.OPEN);
            openedAt = Instant.now();
            log.warn("[BREAKER] {} → OPEN | 连续{}次失败, 熔断{}s", name, failures, recoveryTimeout.getSeconds());
        } else if (state.get() == State.HALF_OPEN) {
            state.set(State.OPEN);
            openedAt = Instant.now();
            log.warn("[BREAKER] {} HALF_OPEN 探测失败 → OPEN", name);
        }
    }

    public boolean isOpen() { return state.get() == State.OPEN; }
    public int failures() { return consecutiveFailures.get(); }
}
