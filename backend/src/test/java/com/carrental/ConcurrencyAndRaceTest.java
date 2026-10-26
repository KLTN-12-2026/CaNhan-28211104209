package com.carrental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import com.carrental.modules.booking.entity.Booking;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.vehicle.service.VehicleService;
import com.carrental.modules.user.service.LicenseService;
import com.carrental.modules.contract.service.ContractService;
import com.carrental.modules.invoice.service.InvoiceService;
import com.carrental.modules.operation.service.IncidentService;
import com.carrental.modules.operation.service.RentalWorkflow;
import com.carrental.modules.operation.repository.IncidentCaseRepository;
import com.carrental.security.Actor;
import com.carrental.exception.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties={
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=IntegrationOnly123!",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(RentalIntegrationTest.TimeConfig.class)
class ConcurrencyAndRaceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            CancelBoundaryTest.verifySafeIsolatedDatabase(url,
                System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
                System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        }
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    static class InjectedFaultException extends RuntimeException {
        public InjectedFaultException(String message) {
            super(message);
        }
    }

    @Autowired BookingService bookingService;
    @Autowired VehicleService vehicleService;
    @Autowired LicenseService licenseService;
    @Autowired RentalWorkflow rentalWorkflow;
    @Autowired IncidentService incidentService;
    @Autowired TransactionTemplate tx;
    @Autowired RentalIntegrationTest.MutableClock clock;

    @SpyBean ContractService contractService;
    @SpyBean InvoiceService invoiceService;
    @SpyBean IncidentCaseRepository incidentCaseRepo;
    @SpyBean JdbcTemplate jdbc;

    private final Actor user = new Actor("demo-user", "USER", "Khách hàng test");
    private final Actor staffHn = new Actor("demo-staff-hn", "STAFF", "Nhân viên Hà Nội");
    private final Actor admin = new Actor("demo-admin", "ADMIN", "Quản trị viên");

    @BeforeEach
    void setupLicenseAndResetSpies() {
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        Mockito.reset(contractService, invoiceService, incidentCaseRepo, jdbc);

        // Dọn dẹp dữ liệu của các ca test trước để tránh xung đột lịch xe / dữ liệu tồn đọng
        jdbc.update("delete from incident_events");
        jdbc.update("delete from incident_cases");
        jdbc.update("delete from vehicle_handover_records");
        jdbc.update("delete from invoice_items");
        jdbc.update("delete from invoices");
        jdbc.update("delete from contracts");
        jdbc.update("delete from booking_status_history");
        jdbc.update("delete from bookings");

        // Đảm bảo các xe mẫu ở trạng thái ACTIVE và chi nhánh gốc chuẩn
        jdbc.update("update vehicles set status='ACTIVE', current_branch_id='hn' where id='v2'");
        jdbc.update("update vehicles set status='ACTIVE', current_branch_id='dn' where id='v3'");
        jdbc.update("update vehicles set status='ACTIVE', current_branch_id='dn' where id='v1'");

        var existingLicense = licenseService.mine(user);
        long licenseVer = existingLicense != null ? existingLicense.version() : 0L;
        licenseService.save(user, new LicenseService.View(
            existingLicense != null ? existingLicense.id() : null,
            "29A-123456", "VN_2025", "B", LocalDate.now().plusYears(2), false, "PENDING", licenseVer
        ));
        LicensePhotoFixture.approved(licenseService, user);
    }

    @Test
    void concurrentBookingAttemptsOnlyOneSucceeds() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicReference<Throwable> otherError = new AtomicReference<>();

        Instant rentStart = clock.instant().plus(Duration.ofDays(10));
        Instant rentEnd = rentStart.plus(Duration.ofHours(24));

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    BookingService.Input input = new BookingService.Input(
                        "v2", "hn", "hn", rentStart, rentEnd, 850000L
                    );
                    bookingService.create(user, input);
                    successCount.incrementAndGet();
                } catch (DomainException e) {
                    if (e.status.value() == 409) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherError.compareAndSet(null, e);
                    }
                } catch (Throwable t) {
                    otherError.compareAndSet(null, t);
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertNull(otherError.get(), () -> "Unexpected error: " + (otherError.get() != null ? otherError.get().getMessage() : ""));
        assertEquals(1, successCount.get(), "Chính xác một yêu cầu đặt xe đồng thời thành công");
        assertEquals(1, conflictCount.get(), "Yêu cầu cạnh tranh phải nhận 409 Conflict");
    }

    @Test
    void snapshotReadThenWriterCommitThenReaderFailsScheduleCheck() throws Exception {
        Instant rentStart = clock.instant().plus(Duration.ofDays(20));
        Instant rentEnd = rentStart.plus(Duration.ofHours(24));

        CountDownLatch readerSnapshotTaken = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> readerError = new AtomicReference<>();
        AtomicReference<Throwable> writerError = new AtomicReference<>();
        AtomicReference<BookingService.View> writerBooking = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Reader - thiết lập read snapshot trước khi writer ghi
        executor.submit(() -> {
            try {
                tx.execute(status -> {
                    // Bước 1: Reader thực hiện non-locking read danh sách booking để tạo snapshot trong transaction
                    var existing = bookingService.bookingIdsForVehicle("v2");
                    readerSnapshotTaken.countDown();

                    // Chờ Writer thực hiện và commit
                    try {
                        if (!writerCommitted.await(10, TimeUnit.SECONDS)) {
                            throw new RuntimeException("Writer did not commit in time");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    // Bước 2: Reader tiếp tục cố gắng tạo booking trùng slot sau khi Writer đã commit
                    BookingService.Input readerInput = new BookingService.Input(
                        "v2", "hn", "hn", rentStart, rentEnd, 850000L
                    );
                    bookingService.create(user, readerInput);
                    return null;
                });
            } catch (DomainException de) {
                readerError.set(de);
            } catch (Throwable t) {
                readerError.set(t);
            }
        });

        // Thread 2: Writer - chờ Reader tạo snapshot rồi tạo & xác nhận booking và commit
        executor.submit(() -> {
            try {
                if (!readerSnapshotTaken.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Reader snapshot was not taken in time");
                }

                // Writer tạo booking và commit trong transaction riêng
                BookingService.Input writerInput = new BookingService.Input(
                    "v2", "hn", "hn", rentStart, rentEnd, 850000L
                );
                BookingService.View b = bookingService.create(user, writerInput);
                writerBooking.set(b);
                writerCommitted.countDown();
            } catch (Throwable t) {
                writerError.set(t);
                writerCommitted.countDown();
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        assertNull(writerError.get(), () -> "Writer error: " + (writerError.get() != null ? writerError.get().getMessage() : ""));
        assertNotNull(writerBooking.get(), "Writer booking must be created");

        assertNotNull(readerError.get(), "Reader must fail due to current-read schedule conflict");
        assertTrue(readerError.get() instanceof DomainException, "Reader error must be DomainException");
        assertEquals(409, ((DomainException) readerError.get()).status.value(),
            "Reader must receive 409 Conflict when current-read detects writer's committed slot");
    }

    @Test
    void concurrentHandoverActionsOnSameBookingOnlyOneWinner() throws Exception {
        Instant baseTime = Instant.parse("2026-11-01T08:00:00Z");
        clock.value = baseTime;

        Instant rentStart = baseTime.plus(Duration.ofHours(2));
        Instant rentEnd = rentStart.plus(Duration.ofHours(24));

        var b1 = bookingService.create(user, new BookingService.Input("v2", "hn", "hn", rentStart, rentEnd, 850000L));
        rentalWorkflow.apply(staffHn, b1.id(), new BookingService.Action("CONFIRMED", b1.version(), "Xác nhận hợp lệ", null, false, false));

        // Dịch chuyển Clock vào giữa khoảng thuê hợp lệ (không bị lỗi giao sớm/no-show)
        clock.value = rentStart.plus(Duration.ofHours(1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        // Hai thread cùng cố gắng giao xe cho đơn b1 (phiên bản ban đầu giống nhau)
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    rentalWorkflow.apply(staffHn, b1.id(), new BookingService.Action(
                        "IN_PROGRESS", b1.version() + 1, null, "Bàn giao xe thực tế", true, false
                    ));
                    successCount.incrementAndGet();
                } catch (DomainException e) {
                    if (e.status.value() == 409) {
                        conflictCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors occurred during handover race: " + unexpectedErrors);
        assertEquals(1, successCount.get(), "Chính xác một thread bàn giao xe thành công (winner)");
        assertEquals(1, conflictCount.get(), "Thread cạnh tranh phải nhận HTTP 409 Conflict do xung đột phiên bản/trạng thái (loser)");

        // Assert trạng thái cuối
        var finalBooking = bookingService.require(user, b1.id());
        assertEquals("IN_PROGRESS", finalBooking.status, "Booking final status must be IN_PROGRESS");

        int handoverCount = jdbc.queryForObject("select count(*) from vehicle_handover_records where booking_id=? and kind='HANDOVER'", Integer.class, b1.id());
        assertEquals(1, handoverCount, "Chính xác một biên bản HANDOVER được ghi");

        int historyCount = jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='IN_PROGRESS'", Integer.class, b1.id());
        assertEquals(1, historyCount, "Chính xác một bản ghi chuyển trạng thái IN_PROGRESS trong history");

        var veh = vehicleService.get("v2");
        assertEquals("ACTIVE", veh.status);
    }

    @Test
    void confirmRaceWithCoordinatedLatchScheduleConflict() throws Exception {
        Instant baseTime = Instant.parse("2026-11-10T08:00:00Z");
        clock.value = baseTime;

        Instant rentStart = baseTime.plus(Duration.ofDays(1));
        Instant rentEnd = rentStart.plus(Duration.ofDays(2));

        // b1 được tạo hợp lệ ở trạng thái PENDING (2 ngày * 850,000 = 1,700,000 VND)
        var b1 = bookingService.create(user, new BookingService.Input("v2", "hn", "hn", rentStart, rentEnd, 1700000L));

        CountDownLatch readerReady = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> readerError = new AtomicReference<>();
        AtomicReference<Throwable> writerError = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Reader - chuẩn bị xác nhận b1 trong cùng một transaction REPEATABLE READ từ non-locking read tạo snapshot đến workflow action
        executor.submit(() -> {
            try {
                tx.execute(status -> {
                    // Non-locking scalar read qua JDBC để thiết lập snapshot view trong REPEATABLE READ mà không nạp Hibernate entity vào L1 cache
                    String vStatus = jdbc.queryForObject("select status from vehicles where id='v2'", String.class);
                    assertEquals("ACTIVE", vStatus, "Reader nhìn thấy xe ACTIVE ở đầu transaction");
                    readerReady.countDown();

                    try {
                        if (!writerCommitted.await(10, TimeUnit.SECONDS)) {
                            throw new RuntimeException("Writer did not commit in time");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    // Reader tiếp tục xác nhận b1 trong cùng transaction:
                    // BookingService.change thực hiện pessimistic lock/current-read (select ... for update) trên xe 'v2',
                    // phát hiện xe thực tế đã bị Writer chuyển sang MAINTENANCE -> 409 Conflict
                    rentalWorkflow.apply(staffHn, b1.id(), new BookingService.Action("CONFIRMED", b1.version(), "Xác nhận b1", null, false, false));
                    return null;
                });
            } catch (DomainException de) {
                readerError.set(de);
            } catch (Throwable t) {
                readerError.set(unwrapDomainException(t));
            }
        });

        // Thread 2: Writer - chuyển xe sang MAINTENANCE và commit
        executor.submit(() -> {
            try {
                if (!readerReady.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Reader ready latch timed out");
                }
                var veh = vehicleService.get("v2");
                vehicleService.operation(staffHn, "v2", "MAINTENANCE", veh.version);
                writerCommitted.countDown();
            } catch (Throwable t) {
                writerError.set(t);
                writerCommitted.countDown();
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        assertNull(writerError.get(), () -> "Writer operation error: " + (writerError.get() != null ? writerError.get().getMessage() : ""));
        assertNotNull(readerError.get(), "Reader must receive conflict when current-read detects vehicle is in MAINTENANCE");
        assertTrue(readerError.get() instanceof DomainException, () -> "Reader error must be DomainException, got: " + readerError.get());
        assertEquals(409, ((DomainException) readerError.get()).status.value());

        // Assert trạng thái cuối
        assertEquals("PENDING", bookingService.require(user, b1.id()).status, "b1 remains PENDING because confirmation was rejected");
        assertEquals("MAINTENANCE", vehicleService.get("v2").status, "Vehicle remains in MAINTENANCE");

        int b1Contracts = jdbc.queryForObject("select count(*) from contracts where booking_id=?", Integer.class, b1.id());
        assertEquals(0, b1Contracts, "b1 must NOT have contract issued");

        int b1History = jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='CONFIRMED'", Integer.class, b1.id());
        assertEquals(0, b1History, "b1 must NOT have CONFIRMED record in history");
    }

    @Test
    void returnRaceWithCoordinatedLatchMaintenanceBlocksSubsequentPickup() throws Exception {
        Instant baseTime = Instant.parse("2026-11-20T08:00:00Z");
        clock.value = baseTime;

        Instant b1Start = baseTime.plus(Duration.ofHours(2));
        Instant b1End = b1Start.plus(Duration.ofDays(1));

        Instant b2Start = b1End.plus(Duration.ofHours(4));
        Instant b2End = b2Start.plus(Duration.ofDays(1));

        var b1 = bookingService.create(user, new BookingService.Input("v2", "hn", "hn", b1Start, b1End, 850000L));
        var b2 = bookingService.create(user, new BookingService.Input("v2", "hn", "hn", b2Start, b2End, 850000L));

        rentalWorkflow.apply(staffHn, b1.id(), new BookingService.Action("CONFIRMED", b1.version(), "Confirm 1", null, false, false));
        rentalWorkflow.apply(staffHn, b2.id(), new BookingService.Action("CONFIRMED", b2.version(), "Confirm 2", null, false, false));

        // Giao xe b1
        clock.value = b1Start.plus(Duration.ofHours(1));
        rentalWorkflow.apply(staffHn, b1.id(), new BookingService.Action("IN_PROGRESS", b1.version() + 1, null, "Giao xe 1", true, false));

        CountDownLatch readerReady = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> readerError = new AtomicReference<>();
        AtomicReference<Throwable> writerError = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Reader - chuẩn bị giao xe b2 trong cùng một transaction REPEATABLE READ từ non-locking read tạo snapshot đến workflow action
        executor.submit(() -> {
            try {
                tx.execute(status -> {
                    // Non-locking scalar read tạo snapshot view trước khi Writer commit trả xe
                    String vStatus = jdbc.queryForObject("select status from vehicles where id='v2'", String.class);
                    assertEquals("ACTIVE", vStatus, "Reader nhìn thấy xe ACTIVE trước khi Writer hoàn tất trả xe");
                    readerReady.countDown();

                    try {
                        if (!writerCommitted.await(10, TimeUnit.SECONDS)) {
                            throw new RuntimeException("Writer did not commit in time");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    // Dịch chuyển giờ đến lượt giao b2
                    clock.value = b2Start.plus(Duration.ofHours(1));

                    // Reader tiếp tục giao xe b2 trong cùng transaction:
                    // BookingService.change khoá xe (select ... for update) phát hiện xe đã bị Writer chuyển sang MAINTENANCE -> 409 Conflict
                    rentalWorkflow.apply(staffHn, b2.id(), new BookingService.Action("IN_PROGRESS", b2.version() + 1, null, "Giao xe 2", true, false));
                    return null;
                });
            } catch (DomainException de) {
                readerError.set(de);
            } catch (Throwable t) {
                readerError.set(unwrapDomainException(t));
            }
        });

        // Thread 2: Writer - trả xe b1 kèm yêu cầu MAINTENANCE và commit
        executor.submit(() -> {
            try {
                if (!readerReady.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Reader ready latch timed out");
                }
                clock.value = b1End.minus(Duration.ofHours(1));
                rentalWorkflow.apply(staffHn, b1.id(), new BookingService.Action("RETURNED", b1.version() + 2, null, "Xe bị hỏng cần bảo dưỡng", false, true));
                writerCommitted.countDown();
            } catch (Throwable t) {
                writerError.set(t);
                writerCommitted.countDown();
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        assertNull(writerError.get(), () -> "Writer return error: " + (writerError.get() != null ? writerError.get().getMessage() : ""));
        assertNotNull(readerError.get(), "Reader handover must be rejected when vehicle enters maintenance");
        assertTrue(readerError.get() instanceof DomainException, () -> "Reader error must be DomainException, got: " + readerError.get());
        assertEquals(409, ((DomainException) readerError.get()).status.value());

        // Assert trạng thái cuối
        assertEquals("RETURNED", bookingService.require(user, b1.id()).status);
        assertEquals("MAINTENANCE", vehicleService.get("v2").status, "Vehicle must be in MAINTENANCE");
        assertEquals("CONFIRMED", bookingService.require(user, b2.id()).status, "b2 must remain CONFIRMED");

        int b2HandoverCount = jdbc.queryForObject("select count(*) from vehicle_handover_records where booking_id=? and kind='HANDOVER'", Integer.class, b2.id());
        assertEquals(0, b2HandoverCount, "No handover record must be created for b2");

        int b2HistoryCount = jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='IN_PROGRESS'", Integer.class, b2.id());
        assertEquals(0, b2HistoryCount, "b2 must NOT have IN_PROGRESS record in history");
    }

    private static Throwable unwrapDomainException(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof DomainException) {
                return curr;
            }
            if (curr.getCause() == null || curr.getCause() == curr) {
                break;
            }
            curr = curr.getCause();
        }
        return t;
    }

    @Test
    void businessFaultInjectionRollbackOnContractIssuance() {
        Instant rentStart = clock.instant().plus(Duration.ofDays(40));
        Instant rentEnd = rentStart.plus(Duration.ofHours(24));
        var b = bookingService.create(user, new BookingService.Input("v2", "hn", "hn", rentStart, rentEnd, 850000L));

        int historyBefore = jdbc.queryForObject("select count(*) from booking_status_history where booking_id=?", Integer.class, b.id());
        int contractsBefore = jdbc.queryForObject("select count(*) from contracts where booking_id=?", Integer.class, b.id());

        // Spy: cho phép issue chạy thật (insert vào contracts) rồi ném InjectedFaultException trong transaction
        Mockito.doAnswer(inv -> {
            inv.callRealMethod();
            throw new InjectedFaultException("Simulated fault injection after contract write");
        }).when(contractService).issue(any(Booking.class));

        InjectedFaultException ex = assertThrows(InjectedFaultException.class, () -> {
            rentalWorkflow.apply(staffHn, b.id(), new BookingService.Action(
                "CONFIRMED", b.version(), "Xác nhận hợp lệ", null, false, false
            ));
        });
        assertEquals("Simulated fault injection after contract write", ex.getMessage());
        Mockito.verify(contractService, Mockito.times(1)).issue(any(Booking.class));

        // Assert sau rollback: trạng thái booking vẫn là PENDING, không có bản ghi hợp đồng, không tăng history
        String currentStatus = jdbc.queryForObject("select status from bookings where id=?", String.class, b.id());
        assertEquals("PENDING", currentStatus, "Booking status must remain PENDING after rollback");

        int historyAfter = jdbc.queryForObject("select count(*) from booking_status_history where booking_id=?", Integer.class, b.id());
        assertEquals(historyBefore, historyAfter, "Booking history must not grow on rolled back confirmation");

        int contractsAfter = jdbc.queryForObject("select count(*) from contracts where booking_id=?", Integer.class, b.id());
        assertEquals(contractsBefore, contractsAfter, "Contracts table must not contain rolled back contract");
        assertEquals(0, contractsAfter);
    }

    @Test
    void businessFaultInjectionRollbackOnInvoiceIssuance() {
        // Spy: khi issue hóa đơn được gọi, thực hiện write thật rồi ném InjectedFaultException
        Mockito.doAnswer(inv -> {
            inv.callRealMethod();
            throw new InjectedFaultException("Simulated fault injection after invoice write");
        }).when(invoiceService).issue(any(Booking.class));

        // Chuẩn bị booking giả lập ở trạng thái RETURNED để gọi COMPLETED
        String bookingId = "test-rollback-inv-" + System.currentTimeMillis();
        jdbc.update("insert into bookings(id,user_id,vehicle_id,customer_name,vehicle_name,pickup_branch_id,return_branch_id,actual_return_branch_id,pickup_name,return_name,start_at,end_at,expires_at,daily_price,rental_days,rental_total,one_way_fee,total,status,policy_version,created_at,version) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            bookingId, "demo-user", "v2", "User", "Veh", "hn", "hn", "hn", "HN", "HN",
            java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(2))),
            java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(1))),
            java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(1))),
            850000L, 1, 850000L, 0L, 850000L, "RETURNED", "rental-2026-09-28",
            java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(3))), 2L
        );

        int totalInvoiceItemsBefore = jdbc.queryForObject("select count(*) from invoice_items", Integer.class);

        InjectedFaultException ex = assertThrows(InjectedFaultException.class, () -> {
            rentalWorkflow.apply(staffHn, bookingId, new BookingService.Action(
                "COMPLETED", 2L, null, null, false, false
            ));
        });
        assertEquals("Simulated fault injection after invoice write", ex.getMessage());
        Mockito.verify(invoiceService, Mockito.times(1)).issue(any(Booking.class));

        // Assert sau rollback: booking status vẫn là RETURNED, không có invoice hay invoice_items
        String status = jdbc.queryForObject("select status from bookings where id=?", String.class, bookingId);
        assertEquals("RETURNED", status, "Booking status must remain RETURNED after rollback");

        int invoicesCount = jdbc.queryForObject("select count(*) from invoices where booking_id=?", Integer.class, bookingId);
        assertEquals(0, invoicesCount, "No invoice record must exist after rollback");

        int totalInvoiceItemsAfter = jdbc.queryForObject("select count(*) from invoice_items", Integer.class);
        assertEquals(totalInvoiceItemsBefore, totalInvoiceItemsAfter, "Global invoice_items table count must not grow");
    }

    @Test
    void businessFaultInjectionRollbackOnIncidentUpdateAndAudit() {
        // Chuẩn bị đơn hoàn tất và tạo hồ sơ sự cố
        String bookingId = "test-incident-rb-" + System.currentTimeMillis();
        Instant tStart = Instant.now().minus(Duration.ofDays(3));
        Instant tEnd = Instant.now().minus(Duration.ofDays(1));

        jdbc.update("insert into bookings(id,user_id,vehicle_id,customer_name,vehicle_name,pickup_branch_id,return_branch_id,actual_return_branch_id,pickup_name,return_name,start_at,end_at,expires_at,daily_price,rental_days,rental_total,one_way_fee,total,status,policy_version,created_at,version) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            bookingId, "demo-user", "v3", "User", "Veh", "dn", "dn", "dn", "DN", "DN",
            java.sql.Timestamp.from(tStart), java.sql.Timestamp.from(tEnd), java.sql.Timestamp.from(tEnd.plus(Duration.ofDays(1))),
            600000L, 2, 1200000L, 0L, 1200000L, "COMPLETED", "rental-2026-09-28",
            java.sql.Timestamp.from(tStart.minus(Duration.ofDays(1))), 5L
        );
        jdbc.update("insert into vehicle_handover_records(id,booking_id,kind,actor_id,branch_id,occurred_at,condition_note,maintenance) values(?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), bookingId, "HANDOVER", "demo-staff-dn", "dn", java.sql.Timestamp.from(tStart), "Giao xe", false);
        jdbc.update("insert into vehicle_handover_records(id,booking_id,kind,actor_id,branch_id,occurred_at,condition_note,maintenance) values(?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), bookingId, "RETURN", "demo-staff-dn", "dn", java.sql.Timestamp.from(tEnd), "Nhan xe", false);

        // Tạo hồ sơ sự cố hợp lệ ban đầu
        var incidentView = incidentService.create(user, new IncidentService.Input(
            bookingId, "DAMAGE", tStart.plus(Duration.ofHours(5)), "Va quyẹt nhẹ", "ref-01", false
        ));
        String caseId = incidentView.id();
        assertEquals("OPEN", incidentView.status());
        assertEquals(0L, incidentView.version());

        int eventsBefore = jdbc.queryForObject("select count(*) from incident_events where case_id=?", Integer.class, caseId);

        // Spy JdbcTemplate: khi câu lệnh INSERT INTO incident_events được thực thi (SAU repo.flush() cập nhật incident_cases),
        // thực hiện write thật xuống DB qua callRealMethod(), sau đó mới ném InjectedFaultException để kiểm chứng rollback dữ liệu đã ghi thực tế.
        AtomicBoolean eventWrittenBeforeFault = new AtomicBoolean(false);
        Mockito.doAnswer(inv -> {
            Object res = inv.callRealMethod(); // Real event insert write!
            eventWrittenBeforeFault.set(true);
            throw new InjectedFaultException("Simulated fault injection after real incident event insert");
        }).when(jdbc).update(Mockito.contains("insert into incident_events"), Mockito.any(Object[].class));

        InjectedFaultException ex = assertThrows(InjectedFaultException.class, () -> {
            incidentService.update(admin, caseId, new IncidentService.Update(
                "ASSESSED", 0L, 2000000L, 500000L, 1500000L, "Báo giá gara ủy quyền", "Đánh giá chi phí sửa chữa"
            ));
        });
        assertEquals("Simulated fault injection after real incident event insert", ex.getMessage());
        assertTrue(eventWrittenBeforeFault.get(), "Real event INSERT must have executed prior to injected fault");
        Mockito.verify(incidentCaseRepo, Mockito.atLeastOnce()).flush();
        Mockito.verify(jdbc, Mockito.atLeastOnce()).update(Mockito.contains("insert into incident_events"), Mockito.any(Object[].class));

        // Assert sau rollback: Case status vẫn là OPEN, version vẫn là 0, assessedDamage vẫn là 0, events không tăng
        var caseRow = jdbc.queryForMap("select status, version, assessed_damage, requested_amount from incident_cases where id=?", caseId);
        assertEquals("OPEN", caseRow.get("status"), "Case status must remain OPEN after rollback");
        assertEquals(0L, ((Number) caseRow.get("version")).longValue(), "Case version must remain unchanged (0)");
        assertEquals(0L, ((Number) caseRow.get("assessed_damage")).longValue(), "Assessed damage must remain 0");
        assertEquals(0L, ((Number) caseRow.get("requested_amount")).longValue(), "Requested amount must remain 0");

        int eventsAfter = jdbc.queryForObject("select count(*) from incident_events where case_id=?", Integer.class, caseId);
        assertEquals(eventsBefore, eventsAfter, "Incident events must not grow on rolled back update");

        var veh = vehicleService.get("v3");
        assertEquals("ACTIVE", veh.status, "Vehicle status must remain unchanged");
    }
}

