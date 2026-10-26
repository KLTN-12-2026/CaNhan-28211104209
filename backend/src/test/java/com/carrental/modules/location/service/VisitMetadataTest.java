package com.carrental.modules.location.service;

import static org.junit.jupiter.api.Assertions.*;
import com.carrental.exception.DomainException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VisitMetadataTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);
    private final VisitMetadata.Evidence source = new VisitMetadata.Evidence("https://example.org/official", clock.instant());
    private VisitMetadata.Hours hours(VisitMetadata.State state, VisitMetadata.Interval... intervals) {
        return new VisitMetadata.Hours(state, List.of(intervals));
    }
    private VisitMetadata metadata(VisitMetadata.Hours day) {
        return new VisitMetadata("Asia/Ho_Chi_Minh", Map.of(DayOfWeek.MONDAY, day), Map.of(), 60, source, source);
    }
    @Test void unknownStaysUnknownAndDateExceptionOverridesWeekly() {
        var closed=hours(VisitMetadata.State.CLOSED);
        var data=new VisitMetadata("Asia/Ho_Chi_Minh",Map.of(DayOfWeek.MONDAY,hours(VisitMetadata.State.OPEN_24H)),
            Map.of(LocalDate.parse("2026-10-19"),closed),null,source,null).validated(clock);
        assertEquals(closed,data.hoursOn(LocalDate.parse("2026-10-19")));
        assertEquals(VisitMetadata.State.UNKNOWN,data.hoursOn(LocalDate.parse("2026-10-20")).state());
        assertEquals(VisitMetadata.State.OPEN_24H,data.hoursOn(LocalDate.parse("2026-10-26")).state());
    }
    @Test void overnightIntervalBelongsToStartingDate() {
        var data=metadata(hours(VisitMetadata.State.INTERVALS,new VisitMetadata.Interval("22:00","02:00",true))).validated(clock);
        assertTrue(data.hoursOn(LocalDate.parse("2026-10-19")).intervals().get(0).nextDay());
    }
    @Test void overnightCannotOverlapNextDayOrDateException() {
        var overnight=hours(VisitMetadata.State.INTERVALS,new VisitMetadata.Interval("22:00","02:00",true));
        var early=hours(VisitMetadata.State.INTERVALS,new VisitMetadata.Interval("01:00","03:00",false));
        assertThrows(DomainException.class,()->new VisitMetadata("Asia/Ho_Chi_Minh",Map.of(DayOfWeek.MONDAY,overnight,DayOfWeek.TUESDAY,early),Map.of(),null,source,null).validated(clock));
        assertThrows(DomainException.class,()->new VisitMetadata("Asia/Ho_Chi_Minh",Map.of(DayOfWeek.MONDAY,overnight),Map.of(LocalDate.parse("2026-10-20"),early),null,source,null).validated(clock));
    }
    @Test void invalidReversedOverlappingAndTooLongIntervalsFail() {
        for(var intervals:List.of(
            List.of(new VisitMetadata.Interval("22:00","02:00",false)),
            List.of(new VisitMetadata.Interval("08:00","17:00",true)),
            List.of(new VisitMetadata.Interval("08:00","12:00",false),new VisitMetadata.Interval("11:00","13:00",false)),
            List.of(new VisitMetadata.Interval("25:00","02:00",true))))
            assertThrows(DomainException.class,()->metadata(new VisitMetadata.Hours(VisitMetadata.State.INTERVALS,intervals)).validated(clock));
    }
    @Test void closedAndUnknownNeverAcceptIntervals() {
        assertThrows(DomainException.class,()->metadata(hours(VisitMetadata.State.CLOSED,new VisitMetadata.Interval("08:00","09:00",false))).validated(clock));
    }
    @Test void verifiedHoursRequireEvidenceAndFutureOrUnsafeSourcesFail() {
        assertThrows(DomainException.class,()->new VisitMetadata("Asia/Ho_Chi_Minh",Map.of(DayOfWeek.MONDAY,hours(VisitMetadata.State.CLOSED)),Map.of(),null,null,null).validated(clock));
        for(var evidence:List.of(new VisitMetadata.Evidence("javascript:alert(1)",clock.instant()),
            new VisitMetadata.Evidence("https://example.org",clock.instant().plusSeconds(1)),
            new VisitMetadata.Evidence("https://user:pass@example.org",clock.instant())))
            assertThrows(DomainException.class,()->new VisitMetadata("Asia/Ho_Chi_Minh",Map.of(),Map.of(),60,null,evidence).validated(clock));
    }
    @Test void durationBoundsAndTimezoneFailClosed() {
        for(int duration:List.of(0,-1,1441))assertThrows(DomainException.class,()->new VisitMetadata("Asia/Ho_Chi_Minh",Map.of(),Map.of(),duration,null,source).validated(clock));
        assertThrows(DomainException.class,()->new VisitMetadata("UTC",Map.of(),Map.of(),null,null,null).validated(clock));
    }
    @Test void fractionalOrStringDurationCannotBeCoercedIntoAnInteger() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        for(String value:List.of("1.5","\"60\"","2147483648"))
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue(
                "{\"timezone\":\"Asia/Ho_Chi_Minh\",\"weekly\":{},\"exceptions\":{},\"durationMinutes\":"+value+"}",VisitMetadata.class));
    }
}
