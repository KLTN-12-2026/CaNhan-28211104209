package com.carrental.modules.booking.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.repository.BookingRepository;
import com.carrental.modules.branch.service.BranchService;
import com.carrental.modules.vehicle.service.VehicleService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleSearchService {
    private final VehicleService vehicles;
    private final BookingRepository bookings;
    private final BranchService branches;
    private final Clock clock;

    public VehicleSearchService(VehicleService vehicles, BookingRepository bookings, BranchService branches, Clock clock) {
        this.vehicles = vehicles; this.bookings = bookings; this.branches = branches; this.clock = clock;
    }

    public record Input(Instant startAt, Instant endAt, String pickupBranchId, String returnBranchId,
                        Long budget, Integer seats, String transmission, String brand, String category, String q,
                        LocalDate pickupDate, LocalDate returnDate) {
        public Input(Instant startAt,Instant endAt,String pickupBranchId,String returnBranchId,Long budget,Integer seats,String transmission,String brand,String category,String q){this(startAt,endAt,pickupBranchId,returnBranchId,budget,seats,transmission,brand,category,q,null,null);}
    }
    public record Item(VehicleService.View vehicle, int rentalDays, long rentalTotal, long oneWayFee, long total) {}
    public record Result(Instant checkedAt, Instant startAt, Instant endAt, String pickupBranchId, String returnBranchId,
                         int rentalDays, List<Item> items) {}

    @Transactional(readOnly = true)
    public Result search(Input input) {
        Instant now = clock.instant();
        boolean calendar = input.pickupDate()!=null || input.returnDate()!=null;
        if(calendar && (input.startAt()!=null || input.endAt()!=null)) throw DomainException.bad("Không trộn ngày thuê với thời gian thuê cũ.");
        var period = calendar ? RentalTerms.calendar(input.pickupDate(),input.returnDate(),now) : null;
        Instant start = calendar ? period.start() : input.startAt(), end = calendar ? period.end() : input.endAt();
        int days = calendar ? period.days() : RentalTerms.days(start,end,now);
        if (input.pickupBranchId() == null || input.pickupBranchId().isBlank() || input.pickupBranchId().length() > 36
                || input.returnBranchId() == null || input.returnBranchId().isBlank() || input.returnBranchId().length() > 36
                || (input.budget() != null && input.budget() < 0)
                || (input.seats() != null && (input.seats() < 1 || input.seats() > 9))
                || (input.transmission() != null && !Set.of("AUTO", "MANUAL").contains(input.transmission()))
                || (input.brand() != null && input.brand().length() > 36)
                || (input.category() != null && input.category().length() > 36)
                || (input.q() != null && input.q().length() > 120)) {
            throw DomainException.bad("Điều kiện tìm xe không hợp lệ.");
        }
        long fee = branches.fee(input.pickupBranchId(), input.returnBranchId());
        String query = input.q() == null ? "" : input.q().strip().toLowerCase(Locale.ROOT);
        Map<String, String> brandNames = new HashMap<>();
        if (!query.isEmpty()) vehicles.catalog().brands().forEach(b -> brandNames.put(b.id(), b.name()));
        var candidates = vehicles.list().stream().filter(v -> "ACTIVE".equals(v.status())
                && (input.seats() == null || v.seats() >= input.seats())
                && (input.transmission() == null || v.transmission().equals(input.transmission()))
                && (input.brand() == null || v.brandId().equals(input.brand()))
                && (input.category() == null || v.categoryId().equals(input.category()))
                && (query.isEmpty() || (v.name() + " " + brandNames.getOrDefault(v.brandId(), "") + " " + v.plate()).toLowerCase(Locale.ROOT).contains(query))).toList();
        Map<String, List<SchedulePolicy.Slot>> schedules = new HashMap<>();
        if (!candidates.isEmpty()) {
            for (var booking : bookings.searchSchedule(candidates.stream().map(VehicleService.View::id).toList(), now)) {
                schedules.computeIfAbsent(booking.vehicleId, key -> new ArrayList<>()).add(new SchedulePolicy.Slot(
                        booking.id, RentalTerms.CALENDAR_POLICY.equals(booking.policyVersion)&&Set.of("RETURNED","COMPLETED").contains(booking.status)?booking.returnBranchId:booking.pickupBranchId, booking.returnBranchId, booking.startAt, booking.endAt, booking.status, booking.expiresAt,RentalTerms.CALENDAR_POLICY.equals(booking.policyVersion)));
            }
        }
        List<Item> items = new ArrayList<>();
        for (var vehicle : candidates) {
            long rent = RentalTerms.rent(vehicle.dailyPrice(), days);
            long total = RentalTerms.total(rent, fee);
            if (input.budget() != null && total > input.budget()) continue;
            var slots = new ArrayList<>(schedules.getOrDefault(vehicle.id(), List.of()));
            slots.add(new SchedulePolicy.Slot("search", input.pickupBranchId(), input.returnBranchId(), start, end,
                    "PENDING", calendar ? period.holdUntil() : RentalTerms.holdUntil(start, now)));
            try {
                SchedulePolicy.validate(vehicle.currentBranchId(), slots, now);
                items.add(new Item(vehicle, days, rent, fee, total));
            } catch (DomainException error) {
                if (error.status != HttpStatus.CONFLICT) throw error;
            }
        }
        items.sort(Comparator.comparing(item -> item.vehicle().name()));
        return new Result(now, start, end, input.pickupBranchId(), input.returnBranchId(), days, List.copyOf(items));
    }
}
