package com.carrental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.booking.entity.Booking;
import com.carrental.modules.vehicle.service.VehicleService;
import com.carrental.modules.user.service.LicenseService;
import com.carrental.modules.operation.service.RentalWorkflow;
import com.carrental.security.Actor;
import com.carrental.exception.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=kdrive_seed_pw_c5_f04_20261009",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(CancelBoundaryTest.TimeConfig.class)
class CancelBoundaryTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            // Fail closed BEFORE Spring boots, Flyway runs, or any app write occurs
            // Use independent read-only probe connection to verify schema, endpoint, and real marker token
            verifySafeIsolatedDatabase(url, System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"), System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        }
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:cancelboundary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    static class MutableClock extends java.time.Clock {
        Instant value = Instant.now();
        public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        public java.time.Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return value; }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    @Autowired BookingService bookingService;
    @Autowired VehicleService vehicleService;
    @Autowired LicenseService licenseService;
    @Autowired RentalWorkflow rentalWorkflow;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired TransactionTemplate tx;

    private final Actor user = new Actor("demo-user", "USER", "Khách hàng test");
    private final Actor otherUser = new Actor("demo-staff-hn", "USER", "Khách hàng khác");
    private final Actor staffHn = new Actor("demo-staff-hn", "STAFF", "Nhân viên Hà Nội");
    private final Actor admin = new Actor("demo-admin", "ADMIN", "Quản trị viên");
    private final Actor guest = new Actor("guest", "GUEST", "Khách");

    public static void verifySafeIsolatedDatabase(JdbcTemplate jdbc) {
        String testUrl = System.getenv("KDRIVE_TEST_DB_URL");
        if (testUrl == null || testUrl.isBlank()) {
            return; // H2 in-memory default
        }
        verifySafeIsolatedDatabase(testUrl, System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"), jdbc);
    }

    public static void verifySafeIsolatedDatabase(String testUrl, String requiredPrefix, String expectedToken, String dbUser, String dbPassword) {
        String expectedHost = System.getenv("KDRIVE_TEST_EXPECTED_HOST");
        String expectedPort = System.getenv("KDRIVE_TEST_EXPECTED_PORT");
        String expectedSchema = System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA");
        verifySafeIsolatedDatabase(testUrl, requiredPrefix, expectedToken, dbUser, dbPassword, expectedHost, expectedPort, expectedSchema);
    }

    public static void verifySafeIsolatedDatabase(String testUrl, String requiredPrefix, String expectedToken, String dbUser, String dbPassword,
                                                  String expectedHost, String expectedPort, String expectedSchema) {
        verifySafeIsolatedDatabaseWithSupplier(testUrl, requiredPrefix, expectedToken, dbUser, dbPassword,
                expectedHost, expectedPort, expectedSchema,
                (url, props) -> java.sql.DriverManager.getConnection(url, props));
    }

    public static void verifySafeIsolatedDatabaseWithSupplier(String testUrl, String requiredPrefix, String expectedToken,
                                                              String dbUser, String dbPassword,
                                                              String expectedHost, String expectedPort, String expectedSchema,
                                                              ConnectionSupplier connectionSupplier) {
        if (testUrl == null || testUrl.isBlank()) {
            return;
        }
        // First perform strict metadata & URL structure validation (fails closed BEFORE opening socket)
        verifyUrlStructure(testUrl, requiredPrefix, expectedToken, expectedHost, expectedPort, expectedSchema);

        if (testUrl.startsWith("jdbc:h2:mem:")) {
            return; // In-memory H2 needs no external probe
        }

        // For MySQL: establish a read-only probe connection BEFORE Spring/Flyway/Hibernate initialization
        java.util.Properties props = new java.util.Properties();
        if (dbUser != null) props.setProperty("user", dbUser);
        if (dbPassword != null) props.setProperty("password", dbPassword);
        props.setProperty("connectTimeout", "5000");

        try (java.sql.Connection conn = connectionSupplier.getConnection(testUrl, props)) {
            // Strictly enforce read-only connection mode on the probe BEFORE any query is executed
            conn.setReadOnly(true);

            // Verify database metadata matches expected schema and URL
            verifyConnectionMetadata(conn, testUrl, expectedSchema);

            // Verify marker table and token scoped to the verified schema (or technical marker schema)
            String markerSchema = System.getenv("KDRIVE_TEST_MARKER_SCHEMA");
            verifyMarkerInConnection(conn, testUrl, expectedToken, expectedSchema, markerSchema);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("[C5-F04 GUARD] Pre-startup database probe failed to connect or verify marker: " + ex.getMessage(), ex);
        }
    }

    @FunctionalInterface
    public interface ConnectionSupplier {
        java.sql.Connection getConnection(String url, java.util.Properties props) throws java.sql.SQLException;
    }

    public static void verifySafeIsolatedDatabase(String testUrl, String requiredPrefix, String expectedToken, JdbcTemplate jdbc) {
        if (testUrl == null || testUrl.isBlank()) {
            return;
        }
        String expectedHost = System.getenv("KDRIVE_TEST_EXPECTED_HOST");
        String expectedPort = System.getenv("KDRIVE_TEST_EXPECTED_PORT");
        String expectedSchema = System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA");
        String markerSchema = System.getenv("KDRIVE_TEST_MARKER_SCHEMA");
        if (markerSchema == null || markerSchema.isBlank()) {
            markerSchema = expectedSchema;
        }
        verifyUrlStructure(testUrl, requiredPrefix, expectedToken, expectedHost, expectedPort, expectedSchema);

        if (testUrl.startsWith("jdbc:h2:mem:")) {
            return;
        }

        if (jdbc != null) {
            try {
                Integer tableExists = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE UPPER(table_schema) = UPPER(?) AND UPPER(table_name) = '__KDRIVE_TEST_FIXTURE_MARKER'",
                    Integer.class,
                    markerSchema
                );
                if (tableExists == null || tableExists == 0) {
                    throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Marker table '__kdrive_test_fixture_marker' not found in database: " + testUrl);
                }
                String tokenQuery = markerSchema.equalsIgnoreCase(expectedSchema)
                    ? "SELECT marker_token FROM __kdrive_test_fixture_marker LIMIT 1"
                    : "SELECT marker_token FROM `" + markerSchema.replace("`", "``") + "`.`__kdrive_test_fixture_marker` WHERE target_schema = ? OR target_schema IS NULL LIMIT 1";
                String tokenInDb = markerSchema.equalsIgnoreCase(expectedSchema)
                    ? jdbc.queryForObject(tokenQuery, String.class)
                    : jdbc.queryForObject(tokenQuery, String.class, expectedSchema);
                if (!expectedToken.equals(tokenInDb)) {
                    throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Fixture marker token mismatch.");
                }
            } catch (Exception ex) {
                if (ex instanceof IllegalStateException ise) throw ise;
                throw new IllegalStateException("[C5-F04 GUARD] Database safety verification query failed: " + ex.getMessage(), ex);
            }
        }
    }

    public static void verifyUrlStructure(String testUrl, String requiredPrefix, String expectedToken) {
        verifyUrlStructure(testUrl, requiredPrefix, expectedToken,
                System.getenv("KDRIVE_TEST_EXPECTED_HOST"),
                System.getenv("KDRIVE_TEST_EXPECTED_PORT"),
                System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA"));
    }

    public static void verifyUrlStructure(String testUrl, String requiredPrefix, String expectedToken,
                                          String expectedHost, String expectedPort, String expectedSchema) {
        if (testUrl == null || testUrl.isBlank()) {
            return;
        }

        // 1. Strict H2 in-memory validation: must start with jdbc:h2:mem:, no TCP/file/mode=mysql bypass
        if (testUrl.startsWith("jdbc:h2:mem:")) {
            return;
        }

        // 2. Reject non-mysql or unverified protocols
        if (!testUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Only verified jdbc:h2:mem: or explicit isolated jdbc:mysql:// URLs are permitted: " + testUrl);
        }

        // 3. Reject any URL containing mode=mysql bypass attempt on non-H2
        if (testUrl.toLowerCase().contains("mode=mysql")) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: 'mode=mysql' parameter is disallowed on MySQL connections: " + testUrl);
        }

        // 4. Parse host, port, and database name precisely instead of loose substring checks
        String clean = testUrl.substring("jdbc:mysql://".length());
        int slashIdx = clean.indexOf('/');
        if (slashIdx == -1) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Malformed JDBC URL missing schema separator '/': " + testUrl);
        }
        String hostPort = clean.substring(0, slashIdx);
        String dbAndParams = clean.substring(slashIdx + 1);
        int qIdx = dbAndParams.indexOf('?');
        String dbName = qIdx == -1 ? dbAndParams : dbAndParams.substring(0, qIdx);
        if (dbName.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Missing schema name in JDBC URL: " + testUrl);
        }

        // Check host and port
        int colonIdx = hostPort.indexOf(':');
        String host = colonIdx == -1 ? hostPort : hostPort.substring(0, colonIdx);
        if (host.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Missing host in JDBC URL: " + testUrl);
        }
        int port = 3306;
        if (colonIdx != -1) {
            try {
                port = Integer.parseInt(hostPort.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Invalid port in JDBC URL: " + hostPort);
            }
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Port " + port + " is out of valid range (1-65535): " + testUrl);
        }

        // MANDATORY: expectedHost, expectedPort, expectedSchema MUST all be supplied for MySQL (fail-closed)
        if (expectedHost == null || expectedHost.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: expectedHost is mandatory for MySQL test execution.");
        }
        if (expectedPort == null || expectedPort.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: expectedPort is mandatory for MySQL test execution.");
        }
        if (expectedSchema == null || expectedSchema.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: expectedSchema is mandatory for MySQL test execution.");
        }

        // Validate parsed values against expected target
        if (!host.equalsIgnoreCase(expectedHost.trim())) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: URL host '" + host + "' does not match expected host '" + expectedHost + "': " + testUrl);
        }

        try {
            int expP = Integer.parseInt(expectedPort.trim());
            if (expP < 1 || expP > 65535) {
                throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Expected port " + expP + " is out of valid range (1-65535).");
            }
            if (port != expP) {
                throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: URL port " + port + " does not match expected port " + expP + ": " + testUrl);
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Invalid expected port configuration: " + expectedPort);
        }

        if (!dbName.equalsIgnoreCase(expectedSchema.trim())) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: URL schema '" + dbName + "' does not match expected schema '" + expectedSchema + "': " + testUrl);
        }

        // Must NOT connect to user database (port 33385, quiz_mysql 3306, or production kdrive)
        if (port == 33385) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Port 33385 is reserved for local user container: " + testUrl);
        }
        if (port == 3306 && (dbName.equals("quiz") || dbName.equals("quiz_mysql") || dbName.startsWith("quiz"))) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Target DB URL matches protected quiz database: " + testUrl);
        }
        if (dbName.equalsIgnoreCase("kdrive")) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Target database name 'kdrive' is protected: " + testUrl);
        }

        // 5. Must require safe fixture schema prefix (MANDATORY fail-closed)
        if (requiredPrefix == null || requiredPrefix.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX environment variable is mandatory for MySQL test execution.");
        }
        if (!dbName.startsWith(requiredPrefix)) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Target database '" + dbName + "' does not start with required prefix '" + requiredPrefix + "': " + testUrl);
        }

        // 6. Must require fixture marker token (MANDATORY fail-closed)
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: KDRIVE_TEST_FIXTURE_MARKER_TOKEN environment variable is mandatory for MySQL test execution.");
        }
    }

    public static void verifyConnectionMetadata(java.sql.Connection conn, String testUrl, String expectedSchema) throws java.sql.SQLException {
        if (expectedSchema == null || expectedSchema.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: expectedSchema is mandatory for metadata verification.");
        }

        String actualSchema = null;
        try {
            actualSchema = conn.getCatalog();
        } catch (Throwable ignored) {}

        if (actualSchema == null || actualSchema.isBlank()) {
            try {
                actualSchema = conn.getSchema();
            } catch (Throwable ignored) {}
        }

        // Fallback: If driver returned null/blank from getCatalog()/getSchema(), query SELECT DATABASE() under read-only probe
        if (actualSchema == null || actualSchema.isBlank()) {
            try (java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("SELECT DATABASE()")) {
                if (rs.next()) {
                    actualSchema = rs.getString(1);
                }
            } catch (Throwable ignored) {}
        }

        // FAIL-CLOSED: if actual schema cannot be determined or is blank, reject immediately
        if (actualSchema == null || actualSchema.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Actual database catalog/schema cannot be determined: " + testUrl);
        }

        if (!actualSchema.equalsIgnoreCase(expectedSchema.trim())) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Actual connection catalog/schema '" + actualSchema + "' does not match expected schema '" + expectedSchema + "': " + testUrl);
        }
    }

    public static void verifyMarkerInConnection(java.sql.Connection conn, String testUrl, String expectedToken) throws java.sql.SQLException {
        verifyMarkerInConnection(conn, testUrl, expectedToken, System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA"), System.getenv("KDRIVE_TEST_MARKER_SCHEMA"));
    }

    public static void verifyMarkerInConnection(java.sql.Connection conn, String testUrl, String expectedToken, String verifiedSchema) throws java.sql.SQLException {
        verifyMarkerInConnection(conn, testUrl, expectedToken, verifiedSchema, System.getenv("KDRIVE_TEST_MARKER_SCHEMA"));
    }

    public static void verifyMarkerInConnection(java.sql.Connection conn, String testUrl, String expectedToken, String verifiedSchema, String markerSchema) throws java.sql.SQLException {
        if (verifiedSchema == null || verifiedSchema.isBlank()) {
            throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: verifiedSchema is mandatory for marker verification (no fallback to all schemas).");
        }

        String searchSchema = (markerSchema != null && !markerSchema.isBlank()) ? markerSchema : verifiedSchema;

        // Query marker specifically scoped ONLY to the verified schema (or explicit technical marker schema)
        String sql = "SELECT count(*) FROM information_schema.tables WHERE UPPER(table_schema) = UPPER(?) AND UPPER(table_name) = '__KDRIVE_TEST_FIXTURE_MARKER'";

        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, searchSchema);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0) {
                    throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Marker table '__kdrive_test_fixture_marker' not found in verified schema '" + searchSchema + "': " + testUrl);
                }
            }
        }

        String queryTokenSql = searchSchema.equalsIgnoreCase(verifiedSchema)
            ? "SELECT marker_token FROM __kdrive_test_fixture_marker LIMIT 1"
            : "SELECT marker_token FROM `" + searchSchema.replace("`", "``") + "`.`__kdrive_test_fixture_marker` WHERE target_schema = ? OR target_schema IS NULL LIMIT 1";

        try (java.sql.PreparedStatement ps = conn.prepareStatement(queryTokenSql)) {
            if (!searchSchema.equalsIgnoreCase(verifiedSchema)) {
                ps.setString(1, verifiedSchema);
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Marker table exists but contains no token: " + testUrl);
                }
                String tokenInDb = rs.getString(1);
                if (!expectedToken.equals(tokenInDb)) {
                    throw new IllegalStateException("[C5-F04 GUARD] Refusing database connection: Fixture marker token mismatch.");
                }
            }
        }
    }

    @BeforeEach
    void setup() {
        verifySafeIsolatedDatabase(jdbc);

        jdbc.update("delete from incident_events");
        jdbc.update("delete from incident_cases");
        jdbc.update("delete from vehicle_handover_records");
        jdbc.update("delete from invoice_items");
        jdbc.update("delete from invoices");
        jdbc.update("delete from contracts");
        jdbc.update("delete from booking_status_history");
        jdbc.update("delete from bookings");

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

    private void cleanupBookings() {
        verifySafeIsolatedDatabase(jdbc);
        jdbc.update("delete from contracts");
        jdbc.update("delete from booking_status_history");
        jdbc.update("delete from bookings");
    }

    private BookingService.View createPendingBooking(Instant startAt, Instant endAt, String vehicleId) {
        return bookingService.create(user, new BookingService.Input(vehicleId, "hn", "hn", startAt, endAt, 850000L));
    }

    private BookingService.View createConfirmedBooking(Instant startAt, Instant endAt, String vehicleId) {
        var pending = createPendingBooking(startAt, endAt, vehicleId);
        return (BookingService.View) rentalWorkflow.apply(staffHn, pending.id(), new BookingService.Action("CONFIRMED", pending.version(), "Xác nhận hợp lệ", null, false, false));
    }

    private BookingService.View castView(Object obj) {
        return (BookingService.View) obj;
    }

    private long getVersion(String bookingId) {
        return bookingService.view(bookingService.require(user, bookingId)).version();
    }

    @Test
    void userCancelExactly24hBeforePickupAllowed() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(24));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        var cancelled = castView(rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Thay đổi kế hoạch", null, false, false)));

        assertEquals("CANCELLED", cancelled.status());
        assertEquals(booking.version() + 1, cancelled.version());
        assertEquals(1, jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='CANCELLED'", Integer.class, booking.id()));
        assertEquals("ACTIVE", vehicleService.get("v2").status);
    }

    @Test
    void userCancel24hPlusEpsilonBeforePickupAllowed() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(24)).plusSeconds(1);
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        var cancelled = castView(rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Thay đổi kế hoạch", null, false, false)));

        assertEquals("CANCELLED", cancelled.status());
        assertEquals(booking.version() + 1, cancelled.version());
    }

    @Test
    void userCancel24hMinusEpsilonBeforePickupBlocked() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(24)).minusSeconds(1);
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Thay đổi kế hoạch", null, false, false))
        );
        assertEquals(400, ex.status.value());
        assertTrue(ex.getMessage().contains("24 giờ"));

        var reloaded = bookingService.require(user, booking.id());
        assertEquals("PENDING", reloaded.status);
        assertEquals(booking.version(), reloaded.version);
        assertEquals(0, jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='CANCELLED'", Integer.class, booking.id()));
    }

    @Test
    void userCancelConfirmedExactly24hBeforePickupAllowed() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(24));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createConfirmedBooking(startAt, endAt, "v2");

        var cancelled = castView(rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Chuyến đi bị hoãn", null, false, false)));

        assertEquals("CANCELLED", cancelled.status());
        assertEquals(booking.version() + 1, cancelled.version());
        assertEquals(1, jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='CANCELLED'", Integer.class, booking.id()));
    }

    @Test
    void userCancelConfirmed24hMinusEpsilonBeforePickupBlocked() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(24)).minusSeconds(1);
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createConfirmedBooking(startAt, endAt, "v2");

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Chuyến đi bị hoãn", null, false, false))
        );
        assertEquals(400, ex.status.value());
        assertTrue(ex.getMessage().contains("24 giờ"));

        var reloaded = bookingService.require(user, booking.id());
        assertEquals("CONFIRMED", reloaded.status);
    }

    @Test
    void adminCancelPendingAlwaysAllowed() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(12));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        var cancelled = castView(rentalWorkflow.apply(admin, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Admin hủy", null, false, false)));

        assertEquals("CANCELLED", cancelled.status());
    }

    @Test
    void adminCancelConfirmedAlwaysAllowed() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(12));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createConfirmedBooking(startAt, endAt, "v2");

        var cancelled = castView(rentalWorkflow.apply(admin, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Admin hủy", null, false, false)));

        assertEquals("CANCELLED", cancelled.status());
    }

    @Test
    void staffCanCancelPendingWhenAssignedToPickup() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        var cancelled = castView(rentalWorkflow.apply(staffHn, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Staff hủy", null, false, false)));

        assertEquals("CANCELLED", cancelled.status());
    }

    @Test
    void guestCannotCancel() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(guest, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Guest hủy", null, false, false))
        );
        assertEquals(403, ex.status.value());
    }

    @Test
    void userBCannotCancelUserABooking() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(otherUser, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "User B hủy", null, false, false))
        );
        assertEquals(403, ex.status.value());
    }

    @Test
    void staleVersionRejected() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version() - 1, "Version cũ", null, false, false))
        );
        assertEquals(409, ex.status.value());
        assertTrue(ex.getMessage().contains("Đơn đã thay đổi"));
    }

    @Test
    void duplicateCancelRejected() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Lần 1", null, false, false));

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version() + 1, "Lần 2", null, false, false))
        );
        assertEquals(409, ex.status.value());
        assertTrue(ex.getMessage().contains("Không thể hủy ở trạng thái này"));
    }

    @Test
    void cancelInProgressRejected() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(2));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var pending = createPendingBooking(startAt, endAt, "v2");
        var confirmed = castView(rentalWorkflow.apply(staffHn, pending.id(), new BookingService.Action("CONFIRMED", pending.version(), "Xác nhận", null, false, false)));

        clock.value = startAt.plusSeconds(1);
        var handed = castView(rentalWorkflow.apply(staffHn, confirmed.id(), new BookingService.Action("IN_PROGRESS", getVersion(confirmed.id()), null, "Giao xe", true, false)));

        long currentVersion = getVersion(handed.id());

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, handed.id(), new BookingService.Action("CANCELLED", currentVersion, "Hủy khi đang thuê", null, false, false))
        );
        assertEquals(409, ex.status.value());
    }

    @Test
    void cancelReturnedRejected() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(2));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var pending = createPendingBooking(startAt, endAt, "v2");
        var confirmed = castView(rentalWorkflow.apply(staffHn, pending.id(), new BookingService.Action("CONFIRMED", pending.version(), "Xác nhận", null, false, false)));
        clock.value = startAt.plusSeconds(1);
        var handed = castView(rentalWorkflow.apply(staffHn, confirmed.id(), new BookingService.Action("IN_PROGRESS", getVersion(confirmed.id()), null, "Giao xe", true, false)));
        clock.value = endAt.minusSeconds(1);
        var returned = castView(rentalWorkflow.apply(staffHn, handed.id(), new BookingService.Action("RETURNED", getVersion(handed.id()), null, "Nhận xe", false, false)));

        long currentVersion = getVersion(returned.id());

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, returned.id(), new BookingService.Action("CANCELLED", currentVersion, "Hủy sau trả", null, false, false))
        );
        assertEquals(409, ex.status.value());
    }

    @Test
    void cancelCompletedRejected() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(2));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var pending = createPendingBooking(startAt, endAt, "v2");
        var confirmed = castView(rentalWorkflow.apply(staffHn, pending.id(), new BookingService.Action("CONFIRMED", pending.version(), "Xác nhận", null, false, false)));
        clock.value = startAt.plusSeconds(1);
        var handed = castView(rentalWorkflow.apply(staffHn, confirmed.id(), new BookingService.Action("IN_PROGRESS", getVersion(confirmed.id()), null, "Giao xe", true, false)));
        clock.value = endAt.minusSeconds(1);
        var returned = castView(rentalWorkflow.apply(staffHn, handed.id(), new BookingService.Action("RETURNED", getVersion(handed.id()), null, "Nhận xe", false, false)));
        var completed = castView(rentalWorkflow.apply(staffHn, returned.id(), new BookingService.Action("COMPLETED", getVersion(returned.id()), null, null, false, false)));

        long currentVersion = getVersion(completed.id());

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, completed.id(), new BookingService.Action("CANCELLED", currentVersion, "Hủy sau hoàn tất", null, false, false))
        );
        assertEquals(409, ex.status.value());
    }

    @Test
    void cancelRejectedRejected() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var pending = createPendingBooking(startAt, endAt, "v2");
        rentalWorkflow.apply(staffHn, pending.id(), new BookingService.Action("REJECTED", pending.version(), "Từ chối", null, false, false));

        long currentVersion = getVersion(pending.id());

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, pending.id(), new BookingService.Action("CANCELLED", currentVersion, "Hủy sau reject", null, false, false))
        );
        assertEquals(409, ex.status.value());
    }

    @Test
    void expiredPendingNoCancelRights() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var pending = createPendingBooking(startAt, endAt, "v2");

        clock.value = startAt.plus(Duration.ofHours(3));

        DomainException ex = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, pending.id(), new BookingService.Action("CANCELLED", pending.version(), "Hủy sau expired", null, false, false))
        );
        assertEquals(409, ex.status.value());
    }

    @Test
    void successfulCancelReleasesSlot() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");
        rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Hủy thành công", null, false, false));

        clock.value = base;
        var newBooking = bookingService.create(user, new BookingService.Input("v2", "hn", "hn", startAt, endAt, 850000L));
        assertEquals("PENDING", newBooking.status());
    }

    @Test
    void successfulCancelPreservesContractSnapshotInvariant() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var pending = createPendingBooking(startAt, endAt, "v2");
        var confirmed = castView(rentalWorkflow.apply(staffHn, pending.id(), new BookingService.Action("CONFIRMED", pending.version(), "Xác nhận", null, false, false)));

        int contractsBefore = jdbc.queryForObject("select count(*) from contracts where booking_id=?", Integer.class, confirmed.id());
        assertEquals(1, contractsBefore);
        var contractBefore = jdbc.queryForMap("select id, booking_id, issued_at, terms_version, snapshot from contracts where booking_id=?", confirmed.id());

        rentalWorkflow.apply(user, confirmed.id(), new BookingService.Action("CANCELLED", confirmed.version(), "Hủy sau confirm", null, false, false));

        int contractsAfter = jdbc.queryForObject("select count(*) from contracts where booking_id=?", Integer.class, confirmed.id());
        assertEquals(1, contractsAfter, "Contract snapshot must be preserved after cancellation");

        var contractAfter = jdbc.queryForMap("select id, booking_id, issued_at, terms_version, snapshot from contracts where booking_id=?", confirmed.id());
        assertEquals(contractBefore.get("id"), contractAfter.get("id"), "Contract ID must remain unchanged");
        assertEquals(contractBefore.get("booking_id"), contractAfter.get("booking_id"), "Contract booking_id must remain unchanged");
        assertEquals(contractBefore.get("issued_at"), contractAfter.get("issued_at"), "Contract issued_at must remain unchanged");
        assertEquals(contractBefore.get("terms_version"), contractAfter.get("terms_version"), "Contract terms_version must remain unchanged");
        assertEquals(contractBefore.get("snapshot"), contractAfter.get("snapshot"), "Contract snapshot payload must be deeply identical before and after cancellation");
    }

    @Test
    void successfulCancelCreatesSingleHistoryEntry() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");
        rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "Lý do hủy", null, false, false));

        int cancelHistoryCount = jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='CANCELLED'", Integer.class, booking.id());
        assertEquals(1, cancelHistoryCount, "Exactly one CANCELLED history entry");

        int totalHistoryCount = jdbc.queryForObject("select count(*) from booking_status_history where booking_id=?", Integer.class, booking.id());
        assertEquals(2, totalHistoryCount, "Total history must contain initial creation + cancellation entry");

        var history = jdbc.queryForList("select previous_status, next_status, actor_id, reason, occurred_at from booking_status_history where booking_id=? and next_status='CANCELLED'", booking.id());
        assertEquals("PENDING", history.get(0).get("previous_status"));
        assertEquals("CANCELLED", history.get(0).get("next_status"));
        assertEquals("demo-user", history.get(0).get("actor_id"));
        assertEquals("Lý do hủy", history.get(0).get("reason"));
        assertNotNull(history.get(0).get("occurred_at"), "Occurred_at must be recorded");
    }

    @Test
    void cancelReasonRequiredAndLengthValidated() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;
        Instant startAt = base.plus(Duration.ofHours(48));
        Instant endAt = startAt.plus(Duration.ofHours(24));

        var booking = createPendingBooking(startAt, endAt, "v2");

        DomainException ex1 = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), null, null, false, false))
        );
        assertEquals(400, ex1.status.value());
        assertTrue(ex1.getMessage().contains("Cần nội dung"));

        DomainException ex2 = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), "", null, false, false))
        );
        assertEquals(400, ex2.status.value());

        String longReason = "x".repeat(2001);
        DomainException ex3 = assertThrows(DomainException.class, () ->
            rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), longReason, null, false, false))
        );
        assertEquals(400, ex3.status.value());
    }

    @Test
    void backendBoundaryCancellationCombinations() {
        Instant base = Instant.parse("2026-10-04T10:00:00Z");
        clock.value = base;

        var testCases = new Object[][] {
            { base.plus(Duration.ofHours(24)), true, "PENDING", "Exactly 24h PENDING" },
            { base.plus(Duration.ofHours(24)).plusSeconds(1), true, "PENDING", "24h+1s PENDING" },
            { base.plus(Duration.ofHours(24)).minusSeconds(1), false, "PENDING", "24h-1s PENDING" },
            { base.plus(Duration.ofHours(24)), true, "CONFIRMED", "Exactly 24h CONFIRMED" },
            { base.plus(Duration.ofHours(24)).plusSeconds(1), true, "CONFIRMED", "24h+1s CONFIRMED" },
            { base.plus(Duration.ofHours(24)).minusSeconds(1), false, "CONFIRMED", "24h-1s CONFIRMED" },
        };

        for (var tc : testCases) {
            cleanupBookings();
            
            Instant startAt = (Instant) tc[0];
            boolean expectedAllowed = (boolean) tc[1];
            String status = (String) tc[2];
            String label = (String) tc[3];

            var booking = "PENDING".equals(status)
                ? createPendingBooking(startAt, startAt.plus(Duration.ofHours(24)), "v2")
                : createConfirmedBooking(startAt, startAt.plus(Duration.ofHours(24)), "v2");

            if (expectedAllowed) {
                assertDoesNotThrow(() ->
                    rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), label, null, false, false)),
                    label + " should be allowed"
                );
            } else {
                DomainException ex = assertThrows(DomainException.class, () ->
                    rentalWorkflow.apply(user, booking.id(), new BookingService.Action("CANCELLED", booking.version(), label, null, false, false)),
                    label + " should be blocked"
                );
                assertEquals(400, ex.status.value());
            }
        }
    }
}