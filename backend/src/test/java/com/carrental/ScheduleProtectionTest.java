package com.carrental;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.SchedulePolicy;
import com.carrental.modules.booking.service.SchedulePolicy.Slot;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleProtectionTest {
    private static final Instant END = Instant.parse("2026-10-27T16:59:59.999999Z");
    private Slot a(boolean calendar) { return new Slot("A", "dn", "dn", END.minusSeconds(172800), END, "IN_PROGRESS", null, calendar); }
    private Slot b() { return new Slot("B", "dn", "dn", END.plusNanos(1000), END.plusSeconds(86400), "CONFIRMED", null, true); }
    @Test void calendarStillAvailableAtLastStoredMicrosecond() {
        assertDoesNotThrow(() -> SchedulePolicy.validate("dn", List.of(a(true), b()), END));
        assertDoesNotThrow(() -> SchedulePolicy.validate("dn", List.of(a(true), b()), END.plusNanos(999)));
    }
    @Test void nextMidnightBlocksEvenDistantSuccessor() {
        var far = new Slot("far", "dn", "dn", END.plusSeconds(864000), END.plusSeconds(950400), "CONFIRMED", null, true);
        var error = assertThrows(DomainException.class, () -> SchedulePolicy.validate("dn", List.of(a(true), far), END.plusNanos(1000)));
        assertEquals(409, error.status.value()); assertTrue(error.getMessage().contains("quá hạn"));
    }
    @Test void legacyStillBlocksAtExactEnd() {
        assertDoesNotThrow(() -> SchedulePolicy.validate("dn", List.of(a(false), b()), END.minusNanos(1)));
        assertThrows(DomainException.class, () -> SchedulePolicy.validate("dn", List.of(a(false), b()), END));
    }
    @Test void earlyWarningDoesNotBlockValidNextDayBooking() {
        assertDoesNotThrow(() -> SchedulePolicy.validate("dn", List.of(a(true), b()), END.minusSeconds(3600)));
    }
    @Test void earlyReturnStillReservesOriginalPeriod() {
        var returned = new Slot("A", "dn", "dn", a(true).start(), END, "RETURNED", null, true);
        var overlap = new Slot("B", "dn", "dn", END.minusSeconds(3600), END.plusSeconds(86400), "CONFIRMED", null, true);
        assertThrows(DomainException.class, () -> SchedulePolicy.validate("dn", List.of(returned, overlap), END.minusSeconds(7200)));
        assertDoesNotThrow(() -> SchedulePolicy.validate("dn", List.of(returned, b()), END.minusSeconds(7200)));
    }
}
