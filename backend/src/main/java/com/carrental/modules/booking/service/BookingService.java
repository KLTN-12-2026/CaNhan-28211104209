package com.carrental.modules.booking.service;

import com.carrental.modules.booking.entity.Booking;
import com.carrental.modules.booking.repository.BookingRepository;
import com.carrental.modules.vehicle.service.VehicleService;
import com.carrental.modules.vehicle.entity.Vehicle;
import com.carrental.modules.branch.service.BranchService;
import com.carrental.modules.user.service.*;
import com.carrental.security.Actor;
import com.carrental.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BookingService {
    private final BookingRepository repo;
    private final VehicleService vehicles;
    private final BranchService branches;
    @org.springframework.beans.factory.annotation.Value("${app.deposit-enabled:false}") private boolean depositEnabled;
    @org.springframework.beans.factory.annotation.Value("${app.deposit-method:VNPAY_SANDBOX}") private String depositMethod;
    @org.springframework.beans.factory.annotation.Value("${app.return-policy-enabled:false}") private boolean returnPolicyEnabled;
    private final ReturnPolicy returnPolicies;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final LicenseService licenses;
    private final AccountService accounts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BookingService(BookingRepository r, VehicleService v, BranchService b, LicenseService l, AccountService a, JdbcTemplate j, Clock c, ReturnPolicy returnPolicies) {
        repo = r; vehicles = v; branches = b; licenses = l; accounts = a; jdbc = j; clock = c; this.returnPolicies = returnPolicies;
    }

    public record Input(String vehicleId, String pickupBranchId, String returnBranchId, Instant startAt, Instant endAt, Long expectedTotal, LocalDate pickupDate, LocalDate returnDate, String returnPolicyVersion, Boolean returnPolicyAccepted) {
        public Input(String vehicleId, String pickupBranchId, String returnBranchId, Instant startAt, Instant endAt, Long expectedTotal, LocalDate pickupDate, LocalDate returnDate) {
            this(vehicleId, pickupBranchId, returnBranchId, startAt, endAt, expectedTotal, pickupDate, returnDate, null, null);
        }
        public Input(String vehicleId, String pickupBranchId, String returnBranchId, Instant startAt, Instant endAt, Long expectedTotal) {
            this(vehicleId, pickupBranchId, returnBranchId, startAt, endAt, expectedTotal, null, null);
        }
    }

    public record View(String id, String userId, String vehicleId, String customerName, String vehicleName,
                       String pickupBranchId, String returnBranchId, String pickupName, String returnName,
                       String actualReturnBranchId, Instant startAt, Instant endAt, Instant expiresAt,
                       String status, long dailyPrice, int rentalDays, long rentalTotal, long oneWayFee,
                       long total, long version, Instant createdAt, String policyVersion, Deposit deposit, ReturnPolicy.View returnPolicy) {}

    public record Deposit(boolean required, long amount, String policy, String method, String mode,
                          Instant approvedAt, Instant paymentDueAt, Instant reviewDueAt, Instant originalHoldUntil,
                          Instant reportedAt, String receiverSnapshot, String txnRef, String cancellationKind,
                          long appliedAmount, long receivedAmount, long missingAmount, long excessAmount) {}

    public View view(Booking b) {
        long received = jdbc.queryForObject(
            "select coalesce(sum(amount),0) from payment_transactions where booking_id=? and status='SUCCEEDED'",
            Long.class, b.id
        );
        long applied = jdbc.queryForObject(
            "select coalesce(sum(applied_amount),0) from payment_transactions where booking_id=? and status='SUCCEEDED'",
            Long.class, b.id
        );
        long missing = Math.max(0, b.depositAmount - received);
        long excess = Math.max(0, received - b.depositAmount);
        String mode = received > b.depositAmount ? "EXCESS" : received >= b.depositAmount ? "FULL" : "PARTIAL";
        return new View(
            b.id, b.userId, b.vehicleId, b.customerName, b.vehicleName,
            b.pickupBranchId, b.returnBranchId, b.pickupName, b.returnName,
            b.actualReturnBranchId, b.startAt, b.endAt, b.expiresAt,
            effective(b), b.dailyPrice, b.rentalDays, b.rentalTotal, b.oneWayFee,
            b.total, b.version, b.createdAt, b.policyVersion,
            new Deposit(
                b.depositRequired, b.depositAmount, b.depositPolicy, b.depositMethod, mode,
                b.depositApprovedAt, b.paymentDueAt, b.reviewDueAt, b.originalHoldUntil,
                b.reportedAt, b.depositReceiverSnapshot, b.depositTxnRef, b.cancellationKind,
                applied, received, missing, excess
            ), returnPolicies.view(b.returnPolicyVersion, b.returnPolicySnapshot, b.returnPolicyAcceptedAt)
        );
    }

    public String effective(Booking b) {
        return "PENDING".equals(b.status) && !b.expiresAt.isAfter(clock.instant()) ? "EXPIRED" : b.status;
    }

    public record Report(long total, Map<String, Long> byStatus, long completed) {}

    public Report report(Instant from, Instant to, Instant asOf) {
        var counts = new LinkedHashMap<String, Long>();
        for (String status : List.of("PENDING", "CONFIRMED", "IN_PROGRESS", "RETURNED", "COMPLETED", "CANCELLED", "REJECTED", "EXPIRED")) {
            counts.put(status, 0L);
        }
        jdbc.query("select case when status='PENDING' and expires_at<=? then 'EXPIRED' else status end as report_status,count(*) as amount from bookings where created_at>=? and created_at<? group by report_status",
            (java.sql.ResultSet rs) -> { counts.put(rs.getString("report_status"), rs.getLong("amount")); },
            java.sql.Timestamp.from(asOf), java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        long completed = jdbc.queryForObject(
            "select count(*) from (select booking_id,min(occurred_at) as completed_at from booking_status_history where next_status='COMPLETED' group by booking_id) first_completion where completed_at>=? and completed_at<?",
            Long.class, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        return new Report(counts.values().stream().mapToLong(Long::longValue).sum(), Collections.unmodifiableMap(counts), completed);
    }

    public boolean canRead(Actor a, Booking b) {
        return a.is("ADMIN") || (a.is("USER") && b.userId.equals(a.id())) ||
               (a.is("STAFF") && (branches.assigned(a, b.pickupBranchId) || branches.assigned(a, b.returnBranchId)));
    }

    public Booking require(Actor a, String id) {
        Booking b = repo.findById(id).orElseThrow(DomainException::missing);
        if (!canRead(a, b)) throw DomainException.missing();
        return b;
    }

    public List<View> list(Actor a) {
        return (a.is("USER") ? repo.findByUserIdOrderByCreatedAtDescIdAsc(a.id()) : repo.findAllByOrderByCreatedAtDescIdAsc())
            .stream().filter(b -> canRead(a, b)).map(this::view).toList();
    }

    public List<String> bookingIdsForVehicle(String vehicleId) {
        return repo.findByVehicleIdOrderByStartAtAsc(vehicleId).stream().map(b -> b.id).toList();
    }

    public Object detail(Actor a, String id) {
        Booking b = require(a, id);
        var result = new LinkedHashMap<String, Object>();
        result.put("booking", view(b));
        result.put("history", jdbc.queryForList("select previous_status,next_status,occurred_at,reason from booking_status_history where booking_id=? order by occurred_at", id));
        result.put("records", jdbc.queryForList("select kind,occurred_at,condition_note,maintenance from vehicle_handover_records where booking_id=? order by occurred_at", id));
        if (a.is("STAFF")) result.put("license", licenses.forBooking(b.userId));
        return result;
    }

    public byte[] licensePhoto(Actor a, String id, String side) {
        return licenses.photo(photoBooking(a, id).userId, side);
    }

    private Booking photoBooking(Actor a, String id) {
        Booking b = require(a, id);
        branches.requireStaff(a, b.pickupBranchId);
        if (!Set.of("PENDING", "CONFIRMED").contains(effective(b)))
            throw DomainException.conflict("Chỉ xem/kiểm tra ảnh cho đơn đang chờ xác nhận hoặc chờ giao xe.");
        return b;
    }

    @Transactional
    public LicenseService.View reviewLicensePhotos(Actor a, String id, long version, boolean approve, String reason) {
        vehicles.lock(repo.findVehicleIdById(id).orElseThrow(DomainException::missing));
        repo.lock(id).orElseThrow(DomainException::missing);
        var b = photoBooking(a, id);
        return licenses.review(b.userId, a, version, approve, reason);
    }

    public SchedulePolicy.Slot slot(Booking b) {
        boolean calendar = RentalTerms.CALENDAR_POLICY.equals(b.policyVersion);
        boolean received = calendar && Set.of("RETURNED", "COMPLETED").contains(b.status);
        return new SchedulePolicy.Slot(b.id, received ? b.returnBranchId : b.pickupBranchId, b.returnBranchId, b.startAt, b.endAt, b.status, b.expiresAt, calendar);
    }

    public void schedule(Vehicle v, Booking added, String excluded) {
        var existing = repo.lockScheduleByVehicleId(v.id);
        var rows = new ArrayList<>(existing.stream().filter(b -> !b.id.equals(excluded)).map(this::slot).toList());
        if (added != null) rows.add(slot(added));
        SchedulePolicy.validate(v.currentBranchId, rows, clock.instant());
    }

    private Booking prepare(Actor a, Input i, Vehicle v) {
        a.require("USER");
        Instant now = clock.instant();
        boolean calendar = i.pickupDate() != null || i.returnDate() != null;
        if (calendar && (i.startAt() != null || i.endAt() != null))
            throw DomainException.bad("Không trộn ngày thuê với thời gian thuê cũ.");
        var period = calendar ? RentalTerms.calendar(i.pickupDate(), i.returnDate(), now) : null;
        Instant start = calendar ? period.start() : i.startAt();
        Instant end = calendar ? period.end() : i.endAt();
        int rentalDays = calendar ? period.days() : RentalTerms.days(start, end, now);
        if (!"ACTIVE".equals(v.status)) throw DomainException.conflict("Xe không hoạt động.");
        licenses.eligible(a.id(), v.transmission, v.seats, end);
        Booking b = new Booking();
        b.userId = a.id();
        b.vehicleId = v.id;
        b.pickupBranchId = i.pickupBranchId();
        b.returnBranchId = i.returnBranchId();
        var route = branches.bookingRoute(i.pickupBranchId(), i.returnBranchId());
        b.pickupName = route.pickupName();
        b.returnName = route.returnName();
        b.oneWayFee = route.fee();
        b.startAt = start;
        b.endAt = end;
        b.createdAt = now;
        b.expiresAt = calendar ? period.holdUntil() : RentalTerms.holdUntil(b.startAt, now);
        b.rentalDays = rentalDays;
        b.dailyPrice = v.dailyPrice;
        b.rentalTotal = RentalTerms.rent(b.dailyPrice, b.rentalDays);
        b.total = RentalTerms.total(b.rentalTotal, b.oneWayFee);
        b.customerName = accounts.profile(a.id()).fullName();
        b.vehicleName = v.name;
        b.status = "PENDING";
        b.policyVersion = calendar ? RentalTerms.CALENDAR_POLICY : "rental-2026-09-28";
        if (returnPolicyEnabled && calendar && b.pickupBranchId.equals(b.returnBranchId)) {
            b.returnPolicyVersion = ReturnPolicy.VERSION;
            b.returnPolicySnapshot = returnPolicies.snapshot(b.dailyPrice);
        }
        if (depositEnabled) {
            b.depositRequired = true;
            b.depositAmount = DepositPolicy.amount(b.rentalTotal);
            b.depositPolicy = "VNPAY_SANDBOX".equals(depositMethod) ? DepositPolicy.VNPAY_VERSION : DepositPolicy.QR_VERSION;
            b.depositMethod = "VNPAY_SANDBOX".equals(depositMethod) ? "VNPAY_SANDBOX" : "DEMO_QR_MANUAL";
            if (DepositPolicy.isVnpayMethod(b.depositMethod) && b.depositAmount < DepositPolicy.DEPOSIT_MIN_AMOUNT)
                throw DomainException.bad("Tiền cọc dưới mức tối thiểu " + DepositPolicy.DEPOSIT_MIN_AMOUNT + " đ; chưa thể đặt xe này.");
        }
        schedule(v, b, null);
        return b;
    }

    @Transactional
    public View quote(Actor a, Input i) {
        return view(prepare(a, i, vehicles.lock(i.vehicleId())));
    }

    @Transactional
    public View create(Actor a, Input i) {
        Booking b = prepare(a, i, vehicles.lock(i.vehicleId()));
        licenses.requirePhotos(a.id(), false);
        if (i.expectedTotal() == null || i.expectedTotal() != b.total)
            throw DomainException.conflict("Giá đã thay đổi hoặc chưa xác nhận báo giá. Vui lòng kiểm tra lại.");
        if (!Objects.equals(i.returnPolicyVersion(), b.returnPolicyVersion))
            throw DomainException.conflict("Chính sách trả xe đã thay đổi. Vui lòng kiểm tra lại báo giá.");
        if (b.returnPolicyVersion != null) {
            if (!Boolean.TRUE.equals(i.returnPolicyAccepted()))
                throw DomainException.bad("Cần xác nhận đã đọc chính sách trả xe sớm/muộn.");
            b.returnPolicyAcceptedAt = clock.instant();
        } else if (Boolean.TRUE.equals(i.returnPolicyAccepted())) {
            throw DomainException.conflict("Chính sách trả xe không áp dụng. Vui lòng kiểm tra lại báo giá.");
        }
        repo.saveAndFlush(b);
        history(b, null, a, "Tạo yêu cầu thuê");
        return view(b);
    }

    public record Action(String next, long version, String reason, String condition, boolean licenseChecked, boolean maintenance, String cancellationKind) {
        public Action(String next, long version, String reason, String condition, boolean licenseChecked, boolean maintenance) {
            this(next, version, reason, condition, licenseChecked, maintenance, null);
        }
    }

    @Transactional
    public Booking change(Actor a, String id, Action action) {
        String vehicleId = repo.findVehicleIdById(id).orElseThrow(DomainException::missing);
        Vehicle vehicle = vehicles.lock(vehicleId);
        Booking b = repo.lock(id).orElseThrow(DomainException::missing);
        if (!canRead(a, b)) throw DomainException.forbidden();
        if (b.version != action.version()) throw DomainException.conflict("Đơn đã thay đổi. Tải lại trước khi xử lý.");
        String old = effective(b), next = action.next();
        if (next == null) throw DomainException.bad("Thiếu trạng thái.");
        switch (next) {
            case "CANCELLED" -> {
                if (!Set.of("PENDING", "CONFIRMED").contains(old)) throw DomainException.conflict("Không thể hủy ở trạng thái này.");
                if (a.is("USER") && Duration.between(clock.instant(), b.startAt).compareTo(Duration.ofHours(24)) < 0)
                    throw DomainException.bad("Cần liên hệ Staff khi còn dưới 24 giờ.");
                reason(action.reason());
                if (b.depositRequired) {
                    b.cancellationKind = a.is("USER") ? "EARLY_USER" : action.cancellationKind();
                    if (!Set.of("EARLY_USER", "BUSINESS", "LATE_CUSTOMER").contains(b.cancellationKind == null ? "" : b.cancellationKind))
                        throw DomainException.bad("Chọn doanh nghiệp hủy hoặc khách hủy sát giờ để xử lý cọc.");
                    if (!a.is("USER") && "EARLY_USER".equals(b.cancellationKind))
                        throw DomainException.bad("Chọn đúng nguyên nhân khách hủy; hệ thống tự kiểm tra mốc 24 giờ.");
                    if ("LATE_CUSTOMER".equals(b.cancellationKind) && DepositPolicy.early(clock.instant(), b.startAt))
                        b.cancellationKind = "EARLY_USER";
                }
                schedule(vehicle, null, b.id);
            }
            case "CONFIRMED", "REJECTED" -> {
                branches.requireStaff(a, b.pickupBranchId);
                if (!"PENDING".equals(old)) throw DomainException.conflict("Đơn không còn chờ xác nhận.");
                if ("CONFIRMED".equals(next) && b.depositRequired)
                    throw DomainException.bad("Đơn có cọc phải duyệt hồ sơ và đóng cọc; không xác nhận thủ công.");
                if ("REJECTED".equals(next)) {
                    b.cancellationKind = "BUSINESS";
                    reason(action.reason());
                    schedule(vehicle, null, b.id);
                } else {
                    if (!"ACTIVE".equals(vehicle.status)) throw DomainException.conflict("Xe không hoạt động.");
                    licenses.eligible(b.userId, vehicle.transmission, vehicle.seats, b.endAt);
                    licenses.requirePhotos(b.userId, true);
                    schedule(vehicle, null, null);
                }
            }
            case "IN_PROGRESS" -> {
                branches.requireStaff(a, b.pickupBranchId);
                if (!"CONFIRMED".equals(old) || clock.instant().isBefore(b.startAt) || !clock.instant().isBefore(b.endAt))
                    throw DomainException.conflict("Chỉ giao đơn đã xác nhận trong khoảng thuê; chưa hỗ trợ giao sớm/no-show.");
                if (RentalTerms.CALENDAR_POLICY.equals(b.policyVersion) &&
                    !clock.instant().atZone(RentalTerms.VIETNAM).toLocalDate().equals(b.startAt.atZone(RentalTerms.VIETNAM).toLocalDate()))
                    throw DomainException.conflict("Chỉ xác nhận giao xe trong ngày nhận đã đặt.");
                if (!"ACTIVE".equals(vehicle.status) || !vehicle.currentBranchId.equals(b.pickupBranchId))
                    throw DomainException.conflict("Xe không sẵn sàng tại chi nhánh nhận.");
                if (repo.lockScheduleByVehicleId(vehicle.id).stream().anyMatch(x -> !x.id.equals(b.id) && "IN_PROGRESS".equals(x.status)))
                    throw DomainException.conflict("Xe còn đang được thuê.");
                if (b.depositRequired) {
                    long applied = jdbc.queryForObject(
                        "select coalesce(sum(applied_amount),0) from payment_transactions where booking_id=? and status='SUCCEEDED'",
                        Long.class, b.id
                    );
                    if (applied < b.depositAmount) throw DomainException.bad("Đơn chưa có cọc đã đối soát đủ.");
                }
                if (!action.licenseChecked()) throw DomainException.bad("Staff phải kiểm tra GPLX thực tế trước giao.");
                reason(action.condition());
                licenses.verify(b.userId, a, vehicle.transmission, vehicle.seats, b.endAt);
            }
            case "RETURNED" -> {
                branches.requireStaff(a, b.returnBranchId);
                if (!"IN_PROGRESS".equals(old)) throw DomainException.conflict("Đơn chưa ở trạng thái đang thuê.");
                reason(action.condition());
                vehicle.currentBranchId = b.returnBranchId;
                b.actualReturnBranchId = b.returnBranchId;
                if (action.maintenance()) vehicle.status = "MAINTENANCE";
            }
            case "COMPLETED" -> {
                branches.requireStaff(a, b.returnBranchId);
                if (!"RETURNED".equals(old)) throw DomainException.conflict("Cần nhận xe trước khi hoàn tất.");
            }
            default -> throw DomainException.bad("Chuyển trạng thái không được hỗ trợ.");
        }
        b.status = next;
        repo.flush();
        history(b, old, a, action.reason() != null ? action.reason() : action.condition());
        return b;
    }

    public Booking lockDepositBooking(String id) {
        vehicles.lock(repo.findVehicleIdById(id).orElseThrow(DomainException::missing));
        var b = repo.lock(id).orElseThrow(DomainException::missing);
        entityManager.refresh(b, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return b;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public View requireDepositStaff(Actor staff,String id) {
        var b=lockDepositBooking(id);branches.requireStaff(staff,b.pickupBranchId);return view(b);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public View depositLock(String id) {
        return view(lockDepositBooking(id));
    }

    @Transactional
    public View approveDeposit(Actor staff, String id, long expectedVersion) {
        var b = lockDepositBooking(id);
        branches.requireStaff(staff, b.pickupBranchId);
        if (b.version != expectedVersion) throw DomainException.conflict("Đơn đã thay đổi. Vui lòng tải lại.");
        if (!b.depositRequired || !"PENDING".equals(effective(b))) throw DomainException.conflict("Đơn không còn chờ duyệt đóng cọc.");

        var v = vehicles.lock(b.vehicleId);
        licenses.eligible(b.userId, v.transmission, v.seats, b.endAt);
        licenses.requirePhotos(b.userId, true);
        schedule(v, null, null);

        Instant now = clock.instant();
        if (b.depositApprovedAt == null) {
            b.depositApprovedAt = now;
            b.originalHoldUntil = b.expiresAt;
            b.paymentDueAt = DepositPolicy.paymentDueAt(now, b.originalHoldUntil);
            b.reviewDueAt = b.paymentDueAt;
            if (DepositPolicy.isDemoQrMethod(b.depositMethod)) {
                b.depositMode = "PARTIAL";
                b.depositReceiverSnapshot = buildReceiverSnapshot(b);
            }
            b.expiresAt = b.paymentDueAt;
        }
        b.depositApprovedBy = staff.id();
        b.depositLicenseVersion = licenses.forBooking(b.userId).version();
        repo.flush();
        return view(b);
    }

    private String buildReceiverSnapshot(Booking b) {
        return """
            Mô phỏng — Không chuyển tiền thật
            Mã yêu cầu: %s
            Số tiền: %d VND
            Chi nhánh nhận: %s
            Tên người nhận: KDrive Demo Receiver
            Ngân hàng: DEMO BANK
            Số tài khoản: 0000000000
            Nội dung chuyển khoản: %s %s
            """.formatted(requestCode(b.id), b.depositAmount, b.pickupName, requestCode(b.id), b.customerName).trim();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public View requirePayable(Actor user, String id) {
        user.require("USER");
        var b = lockDepositBooking(id);
        if (!b.userId.equals(user.id())) throw DomainException.missing();
        validateDeposit(b);
        return view(b);
    }

    private void validateDeposit(Booking b) {
        if (!b.depositRequired || b.depositApprovedAt == null || !"PENDING".equals(effective(b)))
            throw DomainException.conflict("Đơn chưa được duyệt đóng cọc hoặc đã hết hạn.");
        var v = vehicles.lock(b.vehicleId);
        if (!"ACTIVE".equals(v.status)) throw DomainException.conflict("Xe không còn sẵn sàng.");
        licenses.eligible(b.userId, v.transmission, v.seats, b.endAt);
        licenses.requirePhotos(b.userId, true);
        if (!Objects.equals(b.depositLicenseVersion, licenses.forBooking(b.userId).version()))
            throw DomainException.conflict("GPLX đã thay đổi sau duyệt. Cần nhân viên kiểm tra lại; hạn cọc không được kéo dài.");
        schedule(v, null, null);
    }

    public record ReportInput(String transactionRef, Instant transferTime, String transferNote, String sourceAccount, String idempotencyKey) {}

    public static String requestCode(String id) { return "KD" + id.replace("-", "").toUpperCase(java.util.Locale.ROOT); }

    @Transactional
    public View reportDeposit(Actor user, String id, ReportInput input) {
        user.require("USER");
        var b = lockDepositBooking(id);
        if (!b.userId.equals(user.id())) throw DomainException.missing();
        if (!DepositPolicy.isDemoQrMethod(b.depositMethod) || b.depositApprovedAt == null)
            throw DomainException.conflict("Đơn chưa được duyệt cọc QR.");
        if (b.reportedAt != null) return view(b); // Repeated reports cannot extend the original extension.
        if (!"PENDING".equals(effective(b)) || !clock.instant().isBefore(b.paymentDueAt))
            throw DomainException.conflict("Đã hết hạn báo chuyển khoản.");
        b.reportedAt = clock.instant();
        b.reviewDueAt = DepositPolicy.reviewDueAt(b.paymentDueAt, b.originalHoldUntil);
        b.expiresAt = b.reviewDueAt;
        jdbc.update("insert into payment_events(id,booking_id,kind,note,actor_id,created_at,event_key) values(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), id, "TRANSFER_REPORTED", "Khách báo đã chuyển; chưa xác minh nhận tiền", user.id(),
            java.sql.Timestamp.from(clock.instant()), "report:" + id);
        repo.flush();
        return view(b);
    }

    public record VerifyInput(String transactionRef, long amountVnd, Instant transferTime, String transferNote,
                              String sourceAccount, String idempotencyKey, long expectedVersion, boolean verified) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public View confirmQrDeposit(String id, String ref) {
        var b = lockDepositBooking(id);
        if ("CONFIRMED".equals(b.status)) return view(b);
        validateDeposit(b);
        b.depositTxnRef = ref;
        b.status = "CONFIRMED";
        repo.flush();
        history(b, "PENDING", null, "Staff xác minh đủ cọc QR DEMO");
        return view(b);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public View confirmPaidDeposit(String id, String ref) {
        var b = lockDepositBooking(id);
        if (ref.equals(b.depositTxnRef)) return view(b);
        if (b.depositTxnRef != null) throw DomainException.conflict("Đơn đã nhận cọc từ giao dịch khác.");
        validateDeposit(b);
        b.depositTxnRef = ref;
        b.status = "CONFIRMED";
        repo.flush();
        history(b, "PENDING", null, "VNPAY Sandbox xác minh cọc: " + ref);
        return view(b);
    }

    @Transactional
    public View noShow(Actor staff, String id, long expectedVersion, boolean contacted, boolean notDelivered, String note) {
        var b = lockDepositBooking(id);
        branches.requireStaff(staff, b.pickupBranchId);
        reason(note);
        if (b.version != expectedVersion) throw DomainException.conflict("Đơn đã thay đổi.");
        if (!contacted || !notDelivered || !"CONFIRMED".equals(b.status) ||
            !clock.instant().atZone(RentalTerms.VIETNAM).toLocalDate().isAfter(b.startAt.atZone(RentalTerms.VIETNAM).toLocalDate()))
            throw DomainException.bad("Chỉ ghi nhận sau hết ngày nhận; cần xác nhận đã liên hệ và chưa giao xe.");
        if (jdbc.queryForObject("select count(*) from vehicle_handover_records where booking_id=? and kind='HANDOVER'", Long.class, id) > 0)
            throw DomainException.conflict("Đã có biên bản giao xe; không xử lý no-show.");
        schedule(vehicles.lock(b.vehicleId), null, b.id);
        b.cancellationKind = "NO_SHOW";
        b.status = "CANCELLED";
        repo.flush();
        history(b, "CONFIRMED", staff, "Khách không đến nhận: " + note);
        return view(b);
    }

    private void reason(String text) {
        if (text == null || text.isBlank() || text.length() > 2000)
            throw DomainException.bad("Cần nội dung/lý do không rỗng, tối đa 2000 ký tự.");
    }

    public void history(Booking b, String old, Actor a, String reason) {
        jdbc.update("insert into booking_status_history(id,booking_id,previous_status,next_status,actor_id,occurred_at,reason) values(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), b.id, old, b.status, a == null ? null : a.id(), java.sql.Timestamp.from(clock.instant()), reason);
    }

}
