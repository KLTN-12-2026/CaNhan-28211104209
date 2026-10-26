package com.carrental.modules.ai.itinerary;

import com.carrental.exception.DomainException;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewRequest;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewResponse;
import com.carrental.modules.ai.itinerary.service.ItineraryPreviewService;
import com.carrental.modules.ai.itinerary.service.QwenItinerarySequencePlanner;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.carrental.modules.location.service.LocationService;
import com.carrental.modules.location.service.LocationService.PublicView;
import com.carrental.modules.location.service.VisitMetadata;
import com.carrental.modules.location.service.VisitMetadata.*;
import com.carrental.security.Actor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItineraryPreviewServiceTest {
    @Mock private LocationService locations;
    @Mock private OsrmRoutingClient routing;
    @Mock private QwenItinerarySequencePlanner planner;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    private final Actor user = new Actor("u1", "USER", "Khách hàng");

    private ItineraryPreviewService service;

    private PublicView locA;
    private PublicView locB;
    private PublicView locC;

    @BeforeEach
    void setUp() {
        service = new ItineraryPreviewService(locations, routing, planner, clock);

        var hoursA = new Hours(State.INTERVALS, List.of(new Interval("07:30", "17:00", false)));
        var metaA = new VisitMetadata("Asia/Ho_Chi_Minh", Map.of(DayOfWeek.TUESDAY, hoursA), Map.of(), 60,
            new Evidence("https://example.com/a", Instant.parse("2026-10-01T00:00:00Z")),
            new Evidence("https://example.com/a", Instant.parse("2026-10-01T00:00:00Z")));

        var hoursB = new Hours(State.INTERVALS, List.of(new Interval("07:00", "17:30", false)));
        var metaB = new VisitMetadata("Asia/Ho_Chi_Minh", Map.of(DayOfWeek.TUESDAY, hoursB), Map.of(), 90,
            new Evidence("https://example.com/b", Instant.parse("2026-10-01T00:00:00Z")),
            new Evidence("https://example.com/b", Instant.parse("2026-10-01T00:00:00Z")));

        var hoursC = new Hours(State.OPEN_24H, List.of());
        var metaC = new VisitMetadata("Asia/Ho_Chi_Minh", Map.of(DayOfWeek.TUESDAY, hoursC), Map.of(), 45,
            new Evidence("https://example.com/c", Instant.parse("2026-10-01T00:00:00Z")),
            new Evidence("https://example.com/c", Instant.parse("2026-10-01T00:00:00Z")));

        locA = new PublicView("loc-a", "cat-1", "Văn hóa", "Bảo tàng Điêu khắc Chăm", new BigDecimal("16.0601"), new BigDecimal("108.2235"), metaA);
        locB = new PublicView("loc-b", "cat-2", "Thắng cảnh", "Di tích Ngũ Hành Sơn", new BigDecimal("16.0041"), new BigDecimal("108.2638"), metaB);
        locC = new PublicView("loc-c", "cat-2", "Bãi biển", "Bãi biển Mỹ Khê", new BigDecimal("16.0620"), new BigDecimal("108.2470"), metaC);
    }

    @Test
    void successfulPreviewWithCalculatedTimelineAndFingerprint() {
        when(locations.activeCatalog(user)).thenReturn(List.of(locA, locB, locC));
        when(planner.planSequence(eq("loc-a"), anyList(), anyInt()))
            .thenReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of("loc-a", "loc-b"), "QWEN_OPTIMIZED"));

        // 2026-10-20 là Thứ Ba
        LocalDate tripDate = LocalDate.parse("2026-10-20");
        ItineraryPreviewRequest req = new ItineraryPreviewRequest(tripDate, "08:00", "14:00", "loc-a", null);

        var leg1 = new OsrmRoutingClient.Leg(10000.0, 900.0); // 10km, 15 phút
        var estimate = new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.VERIFIED, 10000.0, 900.0, "OSM", List.of(leg1));

        when(routing.route(anyList())).thenReturn(estimate);

        ItineraryPreviewResponse res = service.preview(user, req);

        assertEquals(ItineraryPreviewResponse.Status.VALIDATED, res.status());
        assertEquals("loc-a", res.criteria().startLocationId());
        assertEquals(2, res.orderedLocationIds().size());
        assertEquals("loc-a", res.orderedLocationIds().get(0));
        assertEquals("loc-b", res.orderedLocationIds().get(1));

        assertNotNull(res.fingerprint());
        assertFalse(res.fingerprint().isBlank());

        // Kiểm tra item 1: Bắt đầu lúc 08:00, dwell 60 phút -> rời lúc 09:00
        var item1 = res.items().get(0);
        assertEquals("08:00", item1.arrival());
        assertEquals("09:00", item1.departure());
        assertEquals(60, item1.dwellMinutes());
        assertEquals(0, item1.waitMinutes());
        assertEquals(15, item1.travelToNextMinutes());
        assertEquals(10000.0, item1.distanceToNextMeters());

        // Kiểm tra item 2: Đến lúc 09:15 (09:00 + 15 phút)
        var item2 = res.items().get(1);
        assertEquals("09:15", item2.arrival());
        assertEquals(90, item2.dwellMinutes());
        assertEquals(0, item2.waitMinutes());
        assertEquals("10:45", item2.departure());

        assertTrue(res.totals().totalDwellMinutes() > 0);
        assertTrue(res.totals().totalTravelMinutes() > 0);
        assertTrue(res.totals().totalDistanceMeters() > 0);
    }

    @Test
    void rejectsPastDateOrPastTimeTodayOrReversedTime() {
        // Ngày hôm qua
        LocalDate pastDate = LocalDate.parse("2026-10-19");
        ItineraryPreviewRequest pastReq = new ItineraryPreviewRequest(pastDate, "08:00", "12:00", "loc-a", null);
        assertThrows(DomainException.class, () -> service.preview(user, pastReq));

        // Ngày hôm nay nhưng giờ bắt đầu trước giờ hiện tại (clock là 07:00 VN)
        LocalDate validDate = LocalDate.parse("2026-10-20");
        ItineraryPreviewRequest pastTimeReq = new ItineraryPreviewRequest(validDate, "06:00", "12:00", "loc-a", null);
        assertThrows(DomainException.class, () -> service.preview(user, pastTimeReq));

        // Giờ bắt đầu sau giờ kết thúc
        ItineraryPreviewRequest invertedReq = new ItineraryPreviewRequest(validDate, "14:00", "09:30", "loc-a", null);
        assertThrows(DomainException.class, () -> service.preview(user, invertedReq));

        // Ngày vượt quá 90 ngày
        LocalDate farFuture = validDate.plusDays(95);
        ItineraryPreviewRequest farReq = new ItineraryPreviewRequest(farFuture, "08:00", "12:00", "loc-a", null);
        assertThrows(DomainException.class, () -> service.preview(user, farReq));
    }

    @Test
    void returnsUnavailableWhenQwenFailsOrIsDisabled() {
        when(locations.activeCatalog(user)).thenReturn(List.of(locA, locB));
        when(planner.planSequence(any(), any(), anyInt()))
            .thenThrow(new QwenItinerarySequencePlanner.QwenPlanningException(503, "Dịch vụ AI hiện không khả dụng (bị tắt)."));

        LocalDate validDate = LocalDate.parse("2026-10-20");
        ItineraryPreviewRequest req = new ItineraryPreviewRequest(validDate, "08:00", "14:00", "loc-a", null);

        var res = service.preview(user, req);
        assertEquals(ItineraryPreviewResponse.Status.UNAVAILABLE, res.status());
        assertTrue(res.notices().get(0).contains("không khả dụng"));
        assertNull(res.fingerprint());
    }

    @Test
    void throwsTooManyRequestsWhenQwenIsBusy() {
        when(locations.activeCatalog(user)).thenReturn(List.of(locA, locB));
        when(planner.planSequence(any(), any(), anyInt()))
            .thenThrow(new QwenItinerarySequencePlanner.QwenPlanningException(429, "Dịch vụ AI đang bận"));

        LocalDate validDate = LocalDate.parse("2026-10-20");
        ItineraryPreviewRequest req = new ItineraryPreviewRequest(validDate, "08:00", "14:00", "loc-a", null);

        DomainException ex = assertThrows(DomainException.class, () -> service.preview(user, req));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status);
    }

    @Test
    void returnsNeedsDataWhenStartLocationHasNoMetadata() {
        PublicView unverified = new PublicView("loc-x", "cat-1", "Loại", "Điểm chưa kiểm chứng", new BigDecimal("16.0"), new BigDecimal("108.0"), null);
        when(locations.activeCatalog(user)).thenReturn(List.of(unverified, locB));

        LocalDate validDate = LocalDate.parse("2026-10-20");
        ItineraryPreviewRequest req = new ItineraryPreviewRequest(validDate, "08:00", "12:00", "loc-x", null);

        var res = service.preview(user, req);
        assertEquals(ItineraryPreviewResponse.Status.NEEDS_DATA, res.status());
        assertNull(res.fingerprint());
    }

    @Test
    void returnsNoFeasiblePlanWhenOsrmHasNoRoute() {
        when(locations.activeCatalog(user)).thenReturn(List.of(locA, locB));
        when(planner.planSequence(any(), any(), anyInt()))
            .thenReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of("loc-a", "loc-b"), "QWEN_OPTIMIZED"));

        LocalDate validDate = LocalDate.parse("2026-10-20");
        ItineraryPreviewRequest req = new ItineraryPreviewRequest(validDate, "08:00", "12:00", "loc-a", null);

        when(routing.route(anyList())).thenReturn(new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.NO_ROUTE, null, null, "OSM"));

        var res = service.preview(user, req);
        assertEquals(ItineraryPreviewResponse.Status.NO_FEASIBLE_PLAN, res.status());
        assertTrue(res.notices().get(0).contains("Không tìm được tuyến đường lái xe"));
    }

    @Test
    void returnsNoFeasiblePlanWhenTimeExceedsEndTime() {
        when(locations.activeCatalog(user)).thenReturn(List.of(locA, locB));
        when(planner.planSequence(any(), any(), anyInt()))
            .thenReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of("loc-a", "loc-b"), "QWEN_OPTIMIZED"));

        LocalDate validDate = LocalDate.parse("2026-10-20");
        // Giờ bắt đầu 08:00, dwell 60, đi 90 phút (đến 10:30), dwell 90 phút -> 12:00 vượt quá endTime 11:00
        ItineraryPreviewRequest req = new ItineraryPreviewRequest(validDate, "08:00", "11:00", "loc-a", null);

        var leg1 = new OsrmRoutingClient.Leg(30000.0, 5400.0); // 90 phút
        when(routing.route(anyList())).thenReturn(new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.VERIFIED, 30000.0, 5400.0, "OSM", List.of(leg1)));

        var res = service.preview(user, req);
        assertEquals(ItineraryPreviewResponse.Status.NO_FEASIBLE_PLAN, res.status());
        assertTrue(res.notices().get(0).contains("vượt quá giờ kết thúc hành trình"));
    }

    @Test
    void nonUserActorIsRejected() {
        Actor staff = new Actor("s", "STAFF", "Nhân viên");
        LocalDate validDate = LocalDate.parse("2026-10-20");
        ItineraryPreviewRequest req = new ItineraryPreviewRequest(validDate, "08:00", "12:00", "loc-a", null);
        assertThrows(DomainException.class, () -> service.preview(staff, req));
    }

    @Test
    void overnightIntervalFromYesterdayIsRecognizedAsOpenToday() {
        // Ngày hôm trước (Monday) có khung giờ 22:00 -> 02:00 nextDay = true
        var prevHours = new Hours(State.INTERVALS, List.of(new Interval("22:00", "02:00", true)));
        var todayHours = new Hours(State.CLOSED, List.of());
        var meta = new VisitMetadata("Asia/Ho_Chi_Minh",
            Map.of(DayOfWeek.MONDAY, prevHours, DayOfWeek.TUESDAY, todayHours),
            Map.of(),
            45,
            new Evidence("https://example.com", Instant.parse("2026-10-01T00:00:00Z")),
            new Evidence("https://example.com", Instant.parse("2026-10-01T00:00:00Z"))
        );

        LocalDate tuesday = LocalDate.parse("2026-10-20");
        List<ItineraryPreviewService.EffectiveInterval> intervals = service.effectiveIntervals(meta, tuesday);
        assertEquals(1, intervals.size());
        assertEquals(0, intervals.get(0).startMinute());
        assertEquals(120, intervals.get(0).endMinute()); // 02:00 = 120 phút
    }

    @Test
    void changedWeeklyScheduleOrEvidenceProducesDifferentFingerprint() {
        when(locations.activeCatalog(user)).thenReturn(List.of(locA, locB));
        when(planner.planSequence(any(), any(), anyInt()))
            .thenReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of("loc-a", "loc-b"), "QWEN_OPTIMIZED"));
        var leg1 = new OsrmRoutingClient.Leg(10000.0, 900.0);
        when(routing.route(anyList())).thenReturn(new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.VERIFIED, 10000.0, 900.0, "OSM", List.of(leg1)));

        LocalDate tripDate = LocalDate.parse("2026-10-20");
        ItineraryPreviewRequest req = new ItineraryPreviewRequest(tripDate, "08:00", "14:00", "loc-a", null);
        ItineraryPreviewResponse res1 = service.preview(user, req);

        // Đổi giờ mở cửa thứ ba của locB từ 07:00 thành 08:00 (vẫn mở kịp cho arrival lúc 09:15)
        var alteredHoursB = new Hours(State.INTERVALS, List.of(new Interval("08:00", "17:30", false)));
        var alteredMetaB = new VisitMetadata("Asia/Ho_Chi_Minh", Map.of(DayOfWeek.TUESDAY, alteredHoursB), Map.of(), 90,
            new Evidence("https://example.com/b", Instant.parse("2026-10-01T00:00:00Z")),
            new Evidence("https://example.com/b", Instant.parse("2026-10-01T00:00:00Z")));
        var alteredLocB = new PublicView("loc-b", "cat-2", "Thắng cảnh", "Di tích Ngũ Hành Sơn", new BigDecimal("16.0041"), new BigDecimal("108.2638"), alteredMetaB);

        when(locations.activeCatalog(user)).thenReturn(List.of(locA, alteredLocB));
        ItineraryPreviewResponse res2 = service.preview(user, req);

        // Schedule timeline vẫn giống nhau nhưng fingerprint PHẢI khác vì facts lịch mở cửa đã thay đổi (F02)
        assertEquals(res1.items().get(1).arrival(), res2.items().get(1).arrival());
        assertNotEquals(res1.fingerprint(), res2.fingerprint());
    }

    @Test
    void revalidateRejectsDuplicateOrNullIdsWithBadRequest() {
        LocalDate tripDate = LocalDate.parse("2026-10-20");

        // Duplicate IDs
        DomainException ex1 = assertThrows(DomainException.class, () ->
            service.revalidateOrderedLocations(user, tripDate, "08:00", "14:00", "loc-a", null,
                List.of("loc-a", "loc-a"), "fp")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex1.status);
        assertTrue(ex1.getMessage().contains("không được trùng lặp"));

        // Null in list
        List<String> listWithNull = new ArrayList<>();
        listWithNull.add("loc-a");
        listWithNull.add(null);
        DomainException ex2 = assertThrows(DomainException.class, () ->
            service.revalidateOrderedLocations(user, tripDate, "08:00", "14:00", "loc-a", null,
                listWithNull, "fp")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex2.status);
    }

    @Test
    void revalidateRejectsLocationMissingDurationWithBadRequest() {
        // Địa điểm locNoDuration thiếu metadata durationMinutes
        var metaNoDur = new VisitMetadata("Asia/Ho_Chi_Minh", Map.of(), Map.of(), null, null, null);
        var locNoDur = new PublicView("loc-no-dur", "cat-1", "Văn hóa", "Địa điểm thiếu thời lượng", new BigDecimal("16.0601"), new BigDecimal("108.2235"), metaNoDur);

        when(locations.activeCatalog(user)).thenReturn(List.of(locA, locNoDur));

        LocalDate tripDate = LocalDate.parse("2026-10-20");
        DomainException ex = assertThrows(DomainException.class, () ->
            service.revalidateOrderedLocations(user, tripDate, "08:00", "14:00", "loc-a", null,
                List.of("loc-a", "loc-no-dur"), "fp")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.status);
        assertTrue(ex.getMessage().contains("thiếu dữ liệu thời lượng tham quan"));
    }

    @Test
    void revalidateRejectsFingerprintMismatchWithConflict() {
        when(locations.activeCatalog(user)).thenReturn(List.of(locA, locB));
        var leg1 = new OsrmRoutingClient.Leg(10000.0, 900.0);
        when(routing.route(anyList())).thenReturn(new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.VERIFIED, 10000.0, 900.0, "OSM", List.of(leg1)));

        LocalDate tripDate = LocalDate.parse("2026-10-20");
        DomainException ex = assertThrows(DomainException.class, () ->
            service.revalidateOrderedLocations(user, tripDate, "08:00", "14:00", "loc-a", null,
                List.of("loc-a", "loc-b"), "stale-fingerprint-12345")
        );
        assertEquals(HttpStatus.CONFLICT, ex.status);
        assertTrue(ex.getMessage().contains("đã thay đổi"));
    }
}
