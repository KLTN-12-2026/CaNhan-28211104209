package com.carrental.modules.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class AiRequestDeadline {
    public static final Duration REQUEST_BUDGET = Duration.ofSeconds(60);

    public static final class DeadlineExceededException extends RuntimeException {
        DeadlineExceededException() {
            super("AI request deadline exceeded.");
        }
    }

    private final long startedAt;
    private final long budgetNanos;
    private final LongSupplier ticker;

    private AiRequestDeadline(Duration budget, LongSupplier ticker) {
        Objects.requireNonNull(budget);
        if (budget.isZero() || budget.isNegative() || budget.compareTo(REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("AI request budget must be between zero and sixty seconds.");
        }
        this.budgetNanos = budget.toNanos();
        this.ticker = Objects.requireNonNull(ticker);
        this.startedAt = ticker.getAsLong();
    }

    public static AiRequestDeadline start() {
        return start(REQUEST_BUDGET);
    }

    public static AiRequestDeadline start(Duration budget) {
        return new AiRequestDeadline(budget, System::nanoTime);
    }

    public static AiRequestDeadline withTicker(Duration budget, LongSupplier ticker) {
        return new AiRequestDeadline(budget, ticker);
    }

    public Duration remaining() {
        long remaining = budgetNanos - (ticker.getAsLong() - startedAt);
        if (remaining <= 0) {
            throw new DeadlineExceededException();
        }
        return Duration.ofNanos(remaining);
    }

    public Duration timeoutFor(Duration providerMaximum) {
        Duration remaining = remaining();
        return remaining.compareTo(providerMaximum) < 0 ? remaining : providerMaximum;
    }

    public void requireRemaining() {
        remaining();
    }

    public long elapsedNanos() {
        return ticker.getAsLong() - startedAt;
    }
}
