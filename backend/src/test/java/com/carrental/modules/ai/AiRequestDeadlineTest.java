package com.carrental.modules.ai;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiRequestDeadlineTest {
    @Test
    void remainingBudgetShrinksAcrossProviderCapsAndExpiresAtTheOriginalBoundary() {
        AtomicLong ticker = new AtomicLong(100);
        var deadline = AiRequestDeadline.withTicker(Duration.ofSeconds(60), ticker::get);

        assertEquals(Duration.ofSeconds(5), deadline.timeoutFor(Duration.ofSeconds(5)));
        ticker.addAndGet(Duration.ofSeconds(56).toNanos());
        assertEquals(Duration.ofSeconds(4), deadline.timeoutFor(Duration.ofSeconds(5)));
        ticker.addAndGet(Duration.ofSeconds(4).toNanos());
        assertThrows(AiRequestDeadline.DeadlineExceededException.class, deadline::requireRemaining);
    }

    @Test
    void requestBudgetCannotExceedTheBackendMaximum() {
        assertThrows(
            IllegalArgumentException.class,
            () -> AiRequestDeadline.start(Duration.ofSeconds(61))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> AiRequestDeadline.start(Duration.ZERO)
        );
    }
}
