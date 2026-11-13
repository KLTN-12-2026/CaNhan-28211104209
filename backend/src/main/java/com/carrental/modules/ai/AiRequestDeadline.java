package com.carrental.modules.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class AiRequestDeadline {
    public static final Duration REQUEST_BUDGET = Duration.ofSeconds(60);

    public enum Operation {
        CRITERIA_EXTRACTION,
        ITINERARY_PREVIEW,
        ITINERARY_SAVE,
        ROUTE_PREVIEW,
        UNSPECIFIED
    }

    public static final class DeadlineExceededException extends RuntimeException {
        DeadlineExceededException() {
            super("AI request deadline exceeded.");
        }

        DeadlineExceededException(Throwable cause) {
            super("AI request deadline exceeded.", cause);
        }
    }

    private final long startedAt;
    private final long budgetNanos;
    private final LongSupplier ticker;
    private final Operation operation;

    private AiRequestDeadline(Duration budget, LongSupplier ticker, Operation operation) {
        Objects.requireNonNull(budget);
        if (budget.isZero() || budget.isNegative() || budget.compareTo(REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("AI request budget must be between zero and sixty seconds.");
        }
        this.budgetNanos = budget.toNanos();
        this.ticker = Objects.requireNonNull(ticker);
        this.operation = Objects.requireNonNull(operation);
        this.startedAt = ticker.getAsLong();
    }

    public static AiRequestDeadline start() {
        return start(REQUEST_BUDGET, Operation.UNSPECIFIED);
    }

    public static AiRequestDeadline start(Duration budget) {
        return start(budget, Operation.UNSPECIFIED);
    }

    public static AiRequestDeadline start(Operation operation) {
        return start(REQUEST_BUDGET, operation);
    }

    public static AiRequestDeadline start(Duration budget, Operation operation) {
        return new AiRequestDeadline(budget, System::nanoTime, operation);
    }

    public static AiRequestDeadline withTicker(Duration budget, LongSupplier ticker) {
        return withTicker(budget, Operation.UNSPECIFIED, ticker);
    }

    public static AiRequestDeadline withTicker(Duration budget, Operation operation, LongSupplier ticker) {
        return new AiRequestDeadline(budget, ticker, operation);
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

    public Operation operation() {
        return operation;
    }
}
