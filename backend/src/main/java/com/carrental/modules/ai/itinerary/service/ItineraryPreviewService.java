package com.carrental.modules.ai.itinerary.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewRequest;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewResponse;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewResponse.*;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.carrental.security.Actor;
import com.carrental.modules.location.service.CatalogRevision;
import com.carrental.modules.location.service.LocationService;
import com.carrental.modules.location.service.LocationService.PublicView;
import com.carrental.modules.location.service.VisitMetadata;
import com.carrental.modules.location.service.VisitMetadata.Interval;
import com.carrental.modules.location.service.VisitMetadata.State;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ItineraryPreviewService {
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String CONTRACT_VERSION = "kdrive-itinerary-v1";
    private static final int PLAN_VERSION = 1;

    private final LocationService locationService;
    private final OsrmRoutingClient osrmRoutingClient;
    private final QwenItinerarySequencePlanner sequencePlanner;
    private final Clock clock;

    public record EffectiveInterval(int startMinute, int endMinute) {}

    public record VerifiedSchedule(
        CriteriaInfo criteria,
        List<String> orderedIds,
        List<PublicView> orderedLocations,
        List<ScheduleItem> items,
        Totals totals,
        String fingerprint
    ) {}

    @org.springframework.beans.factory.annotation.Autowired
    public ItineraryPreviewService(
            LocationService locationService,
            OsrmRoutingClient osrmRoutingClient,
            QwenItinerarySequencePlanner sequencePlanner,
            Clock clock) {
        this.locationService = locationService;
        this.osrmRoutingClient = osrmRoutingClient;
        this.sequencePlanner = sequencePlanner;
        this.clock = clock;
    }

    public ItineraryPreviewResponse preview(Actor actor, ItineraryPreviewRequest request) {
        actor.require("USER");
        validateRequest(request);

        ZonedDateTime nowVn = ZonedDateTime.now(clock.withZone(VN_ZONE));
        LocalDate today = nowVn.toLocalDate();
        if (request.tripDate().isBefore(today)) {
            throw DomainException.bad("Ngày hành trình không được ở trong quá khứ.");
        }
        if (request.tripDate().isAfter(today.plusDays(90))) {
            throw DomainException.bad("Ngày hành trình chỉ được lên lịch trong vòng 90 ngày tới.");
        }

        LocalTime startTime = request.parsedStartTime();
        LocalTime endTime = request.parsedEndTime();
        if (!startTime.isBefore(endTime)) {
            throw DomainException.bad("Giờ bắt đầu phải trước giờ kết thúc.");
        }

        // Chặn giờ hôm nay đã qua (F03)
        if (request.tripDate().isEqual(today) && startTime.isBefore(nowVn.toLocalTime())) {
            throw DomainException.bad("Giờ bắt đầu trong ngày hôm nay phải ở tương lai.");
        }

        List<PublicView> catalog = locationService.activeCatalog(actor);
        PublicView startLocation = catalog.stream()
            .filter(l -> l.id().equals(request.startLocationId()))
            .findFirst()
            .orElseThrow(() -> DomainException.bad("Địa điểm bắt đầu không tồn tại trong danh mục hoạt động."));

        CriteriaInfo criteria = new CriteriaInfo(
            CONTRACT_VERSION,
            PLAN_VERSION,
            request.tripDate().toString(),
            request.startTime(),
            request.endTime(),
            startLocation.id(),
            startLocation.name(),
            request.preferredCategoryId()
        );

        if (startLocation.metadata() == null || startLocation.metadata().durationMinutes() == null) {
            return needsData(criteria, List.of("Địa điểm bắt đầu thiếu thông tin lịch trình hoặc thời lượng tham quan được kiểm chứng."));
        }

        List<EffectiveInterval> startIntervals = effectiveIntervals(startLocation.metadata(), request.tripDate());
        if (startIntervals.isEmpty()) {
            return noFeasiblePlan(criteria, List.of(startLocation.id()),
                List.of("Địa điểm bắt đầu '" + startLocation.name() + "' đóng cửa hoặc chưa có lịch mở cửa vào ngày " + request.tripDate() + "."));
        }

        // Lọc candidate pool: ACTIVE, có metadata, thời lượng hợp lệ, mở cửa ngày đó
        List<PublicView> candidateViews = catalog.stream()
            .filter(l -> l.metadata() != null && l.metadata().durationMinutes() != null && l.metadata().durationMinutes() > 0)
            .filter(l -> !effectiveIntervals(l.metadata(), request.tripDate()).isEmpty())
            .toList();

        if (candidateViews.size() < 2) {
            return needsData(criteria, List.of("Không đủ địa điểm có thông tin mở cửa hợp lệ để lập hành trình tối thiểu 2 điểm."));
        }

        // Ưu tiên theo preferredCategoryId nếu có
        List<PublicView> filteredCandidates = new ArrayList<>();
        filteredCandidates.add(startLocation);
        for (PublicView view : candidateViews) {
            if (!view.id().equals(startLocation.id())) {
                if (request.preferredCategoryId() != null && !request.preferredCategoryId().isBlank()
                        && view.categoryId().equals(request.preferredCategoryId())) {
                    filteredCandidates.add(view);
                }
            }
        }
        for (PublicView view : candidateViews) {
            if (!filteredCandidates.contains(view)) {
                filteredCandidates.add(view);
            }
        }
        if (filteredCandidates.size() > 8) {
            filteredCandidates = filteredCandidates.subList(0, 8);
        }

        int availableWindowMinutes = (endTime.getHour() * 60 + endTime.getMinute()) - (startTime.getHour() * 60 + startTime.getMinute());
        int targetCount = 2;
        if (availableWindowMinutes >= 360 && filteredCandidates.size() >= 4) {
            targetCount = 4;
        } else if (availableWindowMinutes >= 240 && filteredCandidates.size() >= 3) {
            targetCount = 3;
        }

        List<QwenItinerarySequencePlanner.Candidate> plannerCandidates = filteredCandidates.stream()
            .map(c -> new QwenItinerarySequencePlanner.Candidate(c.id(), c.name(), c.categoryName()))
            .toList();

        QwenItinerarySequencePlanner.SequenceResult planResult;
        try {
            planResult = sequencePlanner.planSequence(startLocation.id(), plannerCandidates, targetCount);
        } catch (QwenItinerarySequencePlanner.QwenPlanningException qpe) {
            if (qpe.getStatusCode() == 429) {
                throw new DomainException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, qpe.getMessage());
            }
            return unavailable(criteria, List.of(), List.of(qpe.getMessage()));
        }

        List<String> orderedIds = planResult.orderedLocationIds();
        Map<String, PublicView> viewMap = new HashMap<>();
        for (PublicView v : filteredCandidates) {
            viewMap.put(v.id(), v);
        }
        List<PublicView> orderedLocations = orderedIds.stream().map(viewMap::get).toList();

        ScheduleComputationResult scheduleResult = computeSchedule(criteria, orderedLocations, request.tripDate(), startTime, endTime);
        if (scheduleResult.errorResponse != null) {
            return scheduleResult.errorResponse;
        }

        return new ItineraryPreviewResponse(
            Status.VALIDATED,
            criteria,
            orderedIds,
            scheduleResult.items,
            scheduleResult.totals,
            scheduleResult.fingerprint,
            List.of()
        );
    }

    public VerifiedSchedule revalidateOrderedLocations(
            Actor actor,
            LocalDate tripDate,
            String startTimeStr,
            String endTimeStr,
            String startLocationId,
            String preferredCategoryId,
            List<String> orderedLocationIds,
            String expectedFingerprint) {
        actor.require("USER");
        if (orderedLocationIds == null || orderedLocationIds.size() < 2 || orderedLocationIds.size() > 4) {
            throw DomainException.bad("Danh sách địa điểm lưu phải có từ 2 đến 4 điểm.");
        }
        for (String id : orderedLocationIds) {
            if (id == null || id.isBlank()) {
                throw DomainException.bad("Danh sách địa điểm lưu không được chứa phần tử trống.");
            }
        }
        if (new HashSet<>(orderedLocationIds).size() != orderedLocationIds.size()) {
            throw DomainException.bad("Các địa điểm trong lộ trình không được trùng lặp.");
        }
        if (startLocationId == null || startLocationId.isBlank()) {
            throw DomainException.bad("Điểm xuất phát không được để trống.");
        }
        if (!orderedLocationIds.get(0).equals(startLocationId)) {
            throw DomainException.bad("Địa điểm đầu tiên trong lộ trình phải là điểm xuất phát.");
        }

        LocalTime startTime;
        LocalTime endTime;
        try {
            startTime = LocalTime.parse(startTimeStr);
            endTime = LocalTime.parse(endTimeStr);
        } catch (DateTimeParseException e) {
            throw DomainException.bad("Định dạng giờ không hợp lệ (HH:mm).");
        }
        if (!startTime.isBefore(endTime)) {
            throw DomainException.bad("Giờ bắt đầu phải trước giờ kết thúc.");
        }

        ZonedDateTime nowVn = ZonedDateTime.now(clock.withZone(VN_ZONE));
        LocalDate today = nowVn.toLocalDate();
        if (tripDate.isBefore(today)) {
            throw DomainException.bad("Ngày hành trình không được ở trong quá khứ.");
        }
        if (tripDate.isAfter(today.plusDays(90))) {
            throw DomainException.bad("Ngày hành trình chỉ được lên lịch trong vòng 90 ngày tới.");
        }
        if (tripDate.isEqual(today) && startTime.isBefore(nowVn.toLocalTime())) {
            throw DomainException.bad("Giờ bắt đầu trong ngày hôm nay phải ở tương lai.");
        }

        List<PublicView> catalog = locationService.activeCatalog(actor);
        Map<String, PublicView> viewMap = new HashMap<>();
        for (PublicView v : catalog) {
            viewMap.put(v.id(), v);
        }

        List<PublicView> orderedLocations = new ArrayList<>();
        for (String id : orderedLocationIds) {
            PublicView view = viewMap.get(id);
            if (view == null) {
                throw DomainException.conflict("Địa điểm trong lộ trình không còn hoạt động hoặc không tồn tại.");
            }
            if (view.metadata() == null || view.metadata().durationMinutes() == null || view.metadata().durationMinutes() <= 0) {
                throw DomainException.bad("Địa điểm '" + view.name() + "' thiếu dữ liệu thời lượng tham quan hợp lệ.");
            }
            orderedLocations.add(view);
        }

        PublicView startLocation = orderedLocations.get(0);
        CriteriaInfo criteria = new CriteriaInfo(
            CONTRACT_VERSION,
            PLAN_VERSION,
            tripDate.toString(),
            startTimeStr,
            endTimeStr,
            startLocation.id(),
            startLocation.name(),
            preferredCategoryId
        );

        ScheduleComputationResult scheduleResult = computeSchedule(criteria, orderedLocations, tripDate, startTime, endTime);
        if (scheduleResult.errorResponse != null) {
            throw DomainException.conflict("Lịch trình không còn khả thi dựa trên thông tin hiện hành: "
                + String.join("; ", scheduleResult.errorResponse.notices()));
        }

        if (expectedFingerprint == null || !expectedFingerprint.equals(scheduleResult.fingerprint)) {
            throw DomainException.conflict("Dữ liệu hành trình hoặc thông tin địa điểm đã thay đổi so với thời điểm xem trước (Stale Fingerprint). Vui lòng xem trước lại.");
        }

        return new VerifiedSchedule(
            criteria,
            orderedLocationIds,
            orderedLocations,
            scheduleResult.items,
            scheduleResult.totals,
            scheduleResult.fingerprint
        );
    }

    private static class ScheduleComputationResult {
        final List<ScheduleItem> items;
        final Totals totals;
        final String fingerprint;
        final ItineraryPreviewResponse errorResponse;

        ScheduleComputationResult(List<ScheduleItem> items, Totals totals, String fingerprint) {
            this.items = items;
            this.totals = totals;
            this.fingerprint = fingerprint;
            this.errorResponse = null;
        }

        ScheduleComputationResult(ItineraryPreviewResponse errorResponse) {
            this.items = null;
            this.totals = null;
            this.fingerprint = null;
            this.errorResponse = errorResponse;
        }
    }

    private ScheduleComputationResult computeSchedule(
            CriteriaInfo criteria,
            List<PublicView> orderedLocations,
            LocalDate tripDate,
            LocalTime startTime,
            LocalTime endTime) {

        List<String> orderedIds = orderedLocations.stream().map(PublicView::id).toList();

        // Lấy định tuyến OSRM cho chuỗi điểm
        List<OsrmRoutingClient.Point> points = orderedLocations.stream()
            .map(l -> new OsrmRoutingClient.Point(l.latitude().doubleValue(), l.longitude().doubleValue()))
            .toList();

        var routeEstimate = osrmRoutingClient.route(points);
        if (routeEstimate.status() == OsrmRoutingClient.Status.NO_ROUTE) {
            return new ScheduleComputationResult(noFeasiblePlan(criteria, orderedIds,
                List.of("Không tìm được tuyến đường lái xe khả thi giữa các điểm tham quan đã chọn.")));
        }
        if (routeEstimate.status() == OsrmRoutingClient.Status.UNAVAILABLE) {
            return new ScheduleComputationResult(unavailable(criteria, orderedIds,
                List.of("Dịch vụ định tuyến OSRM hiện không khả dụng.")));
        }

        List<OsrmRoutingClient.Leg> legs = routeEstimate.legs();
        if (legs.size() != orderedLocations.size() - 1) {
            if (orderedLocations.size() == 2 && routeEstimate.distanceMeters() != null && routeEstimate.durationSeconds() != null) {
                legs = List.of(new OsrmRoutingClient.Leg(routeEstimate.distanceMeters(), routeEstimate.durationSeconds()));
            } else {
                List<OsrmRoutingClient.Leg> fetchedLegs = new ArrayList<>();
                boolean allLegsOk = true;
                for (int i = 0; i < points.size() - 1; i++) {
                    var pairRoute = osrmRoutingClient.route(List.of(points.get(i), points.get(i + 1)));
                    if (pairRoute.status() != OsrmRoutingClient.Status.VERIFIED) {
                        allLegsOk = false;
                        break;
                    }
                    fetchedLegs.add(new OsrmRoutingClient.Leg(pairRoute.distanceMeters(), pairRoute.durationSeconds()));
                }
                if (!allLegsOk || fetchedLegs.size() != orderedLocations.size() - 1) {
                    return new ScheduleComputationResult(noFeasiblePlan(criteria, orderedIds,
                        List.of("Không thể xác định khoảng cách từng chặng giữa các điểm.")));
                }
                legs = fetchedLegs;
            }
        }

        LocalTime current = startTime;
        List<ScheduleItem> items = new ArrayList<>();
        List<Integer> dwellList = new ArrayList<>();
        List<Integer> waitList = new ArrayList<>();
        List<Integer> travelMinsList = new ArrayList<>();
        List<Double> distanceMetersList = new ArrayList<>();
        List<String> revisions = new ArrayList<>();

        for (int i = 0; i < orderedLocations.size(); i++) {
            PublicView loc = orderedLocations.get(i);
            revisions.add(computeRevision(loc));

            LocalTime arrival = current;
            int dwell = loc.metadata().durationMinutes();
            List<EffectiveInterval> intervals = effectiveIntervals(loc.metadata(), tripDate);

            int wait = calculateWaitMinutes(arrival, dwell, intervals);
            if (wait < 0) {
                return new ScheduleComputationResult(noFeasiblePlan(criteria, orderedIds,
                    List.of("Địa điểm '" + loc.name() + "' không có khung giờ mở cửa phù hợp cho thời điểm đến lúc " + arrival + ".")));
            }

            int arrivalMins = arrival.getHour() * 60 + arrival.getMinute();
            int departureMins = arrivalMins + wait + dwell;
            if (departureMins >= 24 * 60) {
                return new ScheduleComputationResult(noFeasiblePlan(criteria, orderedIds,
                    List.of("Thời gian kết thúc tham quan tại '" + loc.name() + "' vượt quá trong ngày.")));
            }

            LocalTime departure = LocalTime.of(departureMins / 60, departureMins % 60);
            if (departure.isAfter(endTime)) {
                return new ScheduleComputationResult(noFeasiblePlan(criteria, orderedIds,
                    List.of("Thời gian tham quan tại '" + loc.name() + "' kết thúc lúc " + departure
                        + ", vượt quá giờ kết thúc hành trình " + endTime + ".")));
            }

            Integer travelMinutes = null;
            Double travelDistanceMeters = null;
            if (i < orderedLocations.size() - 1) {
                OsrmRoutingClient.Leg leg = legs.get(i);
                travelDistanceMeters = leg.distanceMeters();
                travelMinutes = (int) Math.ceil(leg.durationSeconds() / 60.0);
                int nextArrivalMins = departureMins + travelMinutes;
                if (nextArrivalMins >= 24 * 60) {
                    return new ScheduleComputationResult(noFeasiblePlan(criteria, orderedIds,
                        List.of("Thời gian di chuyển từ '" + loc.name() + "' đến điểm kế tiếp vượt quá trong ngày.")));
                }
                current = LocalTime.of(nextArrivalMins / 60, nextArrivalMins % 60);
            }

            dwellList.add(dwell);
            waitList.add(wait);
            travelMinsList.add(travelMinutes);
            distanceMetersList.add(travelDistanceMeters);

            var hoursEvidence = loc.metadata().hoursEvidence();
            var durationEvidence = loc.metadata().durationEvidence();
            String fullHours = formatHoursDescription(intervals);

            items.add(new ScheduleItem(
                i + 1,
                loc.id(),
                loc.name(),
                loc.categoryName(),
                loc.latitude().doubleValue(),
                loc.longitude().doubleValue(),
                arrival.toString(),
                departure.toString(),
                dwell,
                wait,
                travelMinutes,
                travelDistanceMeters,
                loc.metadata().hoursOn(tripDate).state().name(),
                fullHours,
                hoursEvidence != null ? hoursEvidence.url() : null,
                hoursEvidence != null && hoursEvidence.verifiedAt() != null ? hoursEvidence.verifiedAt().toString() : null,
                durationEvidence != null ? durationEvidence.url() : null,
                durationEvidence != null && durationEvidence.verifiedAt() != null ? durationEvidence.verifiedAt().toString() : null,
                "© OpenStreetMap contributors; OSRM driving estimate",
                CONTRACT_VERSION,
                loc.metadata()
            ));
        }

        Totals totals = new Totals(
            dwellList.stream().mapToInt(Integer::intValue).sum(),
            waitList.stream().mapToInt(Integer::intValue).sum(),
            travelMinsList.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum(),
            distanceMetersList.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum(),
            orderedLocations.size()
        );

        String fingerprint = ItineraryFingerprint.compute(
            tripDate.toString(),
            startTime.toString(),
            endTime.toString(),
            criteria.startLocationId(),
            criteria.preferredCategoryId(),
            orderedIds,
            revisions,
            dwellList,
            waitList,
            travelMinsList,
            distanceMetersList
        );

        return new ScheduleComputationResult(items, totals, fingerprint);
    }

    public List<EffectiveInterval> effectiveIntervals(VisitMetadata metadata, LocalDate date) {
        if (metadata == null) return List.of();

        List<EffectiveInterval> list = new ArrayList<>();

        // 1. Kiểm tra ngày hôm trước có khung giờ qua đêm sang hôm nay (F03)
        LocalDate prevDate = date.minusDays(1);
        VisitMetadata.Hours prevHours = metadata.hoursOn(prevDate);
        if (prevHours.state() == State.INTERVALS && prevHours.intervals() != null) {
            for (Interval iv : prevHours.intervals()) {
                if (iv.nextDay()) {
                    int endMins = parseMinutes(iv.end());
                    if (endMins > 0) {
                        list.add(new EffectiveInterval(0, endMins));
                    }
                }
            }
        }

        // 2. Khung giờ của chính ngày date
        VisitMetadata.Hours todayHours = metadata.hoursOn(date);
        if (todayHours.state() == State.OPEN_24H) {
            list.add(new EffectiveInterval(0, 1440));
        } else if (todayHours.state() == State.INTERVALS && todayHours.intervals() != null) {
            for (Interval iv : todayHours.intervals()) {
                int start = parseMinutes(iv.start());
                int end = parseMinutes(iv.end()) + (iv.nextDay() ? 1440 : 0);
                list.add(new EffectiveInterval(start, end));
            }
        }

        return Collections.unmodifiableList(list);
    }

    private static int calculateWaitMinutes(LocalTime arrival, int dwell, List<EffectiveInterval> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return -1;
        }

        int arrivalMins = arrival.getHour() * 60 + arrival.getMinute();
        for (EffectiveInterval interval : intervals) {
            if (arrivalMins >= interval.startMinute() && arrivalMins < interval.endMinute()) {
                if (arrivalMins + dwell <= interval.endMinute()) {
                    return 0;
                }
            } else if (arrivalMins < interval.startMinute()) {
                if (interval.startMinute() + dwell <= interval.endMinute()) {
                    return interval.startMinute() - arrivalMins;
                }
            }
        }
        return -1;
    }

    private static String formatHoursDescription(List<EffectiveInterval> intervals) {
        if (intervals == null || intervals.isEmpty()) return "Đóng cửa / Không rõ";
        List<String> parts = new ArrayList<>();
        for (EffectiveInterval iv : intervals) {
            int sH = iv.startMinute() / 60;
            int sM = iv.startMinute() % 60;
            int eH = iv.endMinute() / 60;
            int eM = iv.endMinute() % 60;
            parts.add(String.format("%02d:%02d-%02d:%02d", sH, sM, eH, eM));
        }
        return String.join(", ", parts);
    }

    private static int parseMinutes(String time) {
        LocalTime t = LocalTime.parse(time);
        return t.getHour() * 60 + t.getMinute();
    }

    private static String canonicalMetadataString(VisitMetadata meta) {
        if (meta == null) return "NO_META";
        StringBuilder sb = new StringBuilder();
        sb.append("tz=").append(meta.timezone() != null ? meta.timezone() : "").append(";");
        sb.append("dur=").append(meta.durationMinutes() != null ? meta.durationMinutes() : "").append(";");

        sb.append("weekly=[");
        if (meta.weekly() != null) {
            for (DayOfWeek day : DayOfWeek.values()) {
                VisitMetadata.Hours h = meta.weekly().get(day);
                sb.append(day.name()).append(":");
                if (h != null) {
                    sb.append(h.state()).append(":");
                    if (h.intervals() != null) {
                        for (VisitMetadata.Interval iv : h.intervals()) {
                            sb.append(iv.start()).append("-").append(iv.end()).append("-").append(iv.nextDay()).append(",");
                        }
                    }
                }
                sb.append("|");
            }
        }
        sb.append("];");

        sb.append("exc=[");
        if (meta.exceptions() != null) {
            var sortedDates = new ArrayList<>(meta.exceptions().keySet());
            Collections.sort(sortedDates);
            for (LocalDate d : sortedDates) {
                VisitMetadata.Hours h = meta.exceptions().get(d);
                sb.append(d.toString()).append(":");
                if (h != null) {
                    sb.append(h.state()).append(":");
                    if (h.intervals() != null) {
                        for (VisitMetadata.Interval iv : h.intervals()) {
                            sb.append(iv.start()).append("-").append(iv.end()).append("-").append(iv.nextDay()).append(",");
                        }
                    }
                }
                sb.append("|");
            }
        }
        sb.append("];");

        sb.append("hoursEv=");
        if (meta.hoursEvidence() != null) {
            sb.append(meta.hoursEvidence().url() != null ? meta.hoursEvidence().url() : "").append("|")
              .append(meta.hoursEvidence().verifiedAt() != null ? meta.hoursEvidence().verifiedAt().toString() : "");
        }
        sb.append(";");

        sb.append("durEv=");
        if (meta.durationEvidence() != null) {
            sb.append(meta.durationEvidence().url() != null ? meta.durationEvidence().url() : "").append("|")
              .append(meta.durationEvidence().verifiedAt() != null ? meta.durationEvidence().verifiedAt().toString() : "");
        }
        sb.append(";");

        return sb.toString();
    }

    private static String computeRevision(PublicView view) {
        return CatalogRevision.of(
            view.id(),
            view.categoryId(),
            view.name(),
            view.latitude().toString(),
            view.longitude().toString(),
            canonicalMetadataString(view.metadata())
        );
    }

    private void validateRequest(ItineraryPreviewRequest request) {
        if (request == null) throw DomainException.bad("Dữ liệu yêu cầu không được để trống.");
        if (request.tripDate() == null) throw DomainException.bad("Ngày hành trình không được để trống.");
        if (request.startTime() == null || !request.startTime().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw DomainException.bad("Giờ bắt đầu phải theo định dạng HH:mm.");
        if (request.endTime() == null || !request.endTime().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw DomainException.bad("Giờ kết thúc phải theo định dạng HH:mm.");
        if (request.startLocationId() == null || request.startLocationId().isBlank())
            throw DomainException.bad("Điểm bắt đầu không được để trống.");
    }

    private ItineraryPreviewResponse needsData(CriteriaInfo criteria, List<String> notices) {
        return new ItineraryPreviewResponse(
            Status.NEEDS_DATA,
            criteria,
            List.of(),
            List.of(),
            null,
            null,
            notices
        );
    }

    private ItineraryPreviewResponse noFeasiblePlan(CriteriaInfo criteria, List<String> orderedIds, List<String> notices) {
        return new ItineraryPreviewResponse(
            Status.NO_FEASIBLE_PLAN,
            criteria,
            orderedIds,
            List.of(),
            null,
            null,
            notices
        );
    }

    private ItineraryPreviewResponse unavailable(CriteriaInfo criteria, List<String> orderedIds, List<String> notices) {
        return new ItineraryPreviewResponse(
            Status.UNAVAILABLE,
            criteria,
            orderedIds,
            List.of(),
            null,
            null,
            notices
        );
    }
}
