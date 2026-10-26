package com.carrental;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit test verifying CancelBoundaryTest.verifySafeIsolatedDatabase implementation.
 * Proves that:
 * 1. Missing token / marker is rejected.
 * 2. Mismatch endpoint / schema / token is rejected.
 * 3. Malformed or unverified protocols are rejected.
 * 4. Protected database URLs (port 33385, quiz_mysql 3306, /kdrive) are rejected immediately.
 * 5. Attempted 'mode=mysql' bypass on non-H2 is rejected.
 * 6. True in-memory H2 (jdbc:h2:mem:...) passes silently without hitting external DB.
 */
class CancelBoundaryGuardUnitTest {

    private static final String VALID_SCHEMA_PREFIX = "c5_test_fixture_";
    private static final String VALID_TOKEN = "valid_token_123456";
    private static final String VALID_ISOLATED_URL = "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1?useSSL=false";

    @Test
    void h2InMemoryUrlPassesGuardSilently() {
        // True H2 in-memory URL passes without any external connection
        assertDoesNotThrow(() -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase("jdbc:h2:mem:test;MODE=MySQL", null, null, null);
        });
        assertDoesNotThrow(() -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(null, null, null, null);
        });
    }

    @Test
    void modeMysqlBypassAttemptOnMySQLIsRejected() {
        // Attack attempt: trying to bypass MySQL guard by appending mode=mysql parameter
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                "jdbc:mysql://127.0.0.1:3306/quiz_mysql?mode=mysql",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                null
            );
        });
        assertTrue(ex.getMessage().contains("mode=mysql") || ex.getMessage().contains("matches protected"));
    }

    @Test
    void protectedDatabaseUrlsFailClosedImmediately() {
        // 1. Port 33385 (User local MySQL container)
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                "jdbc:mysql://127.0.0.1:33385/c5_test_fixture_ag",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                null
            );
        });

        // 2. Port 3306 quiz database
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                "jdbc:mysql://127.0.0.1:3306/quiz_mysql",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                null
            );
        });

        // 3. Protected production kdrive database name
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                "jdbc:mysql://127.0.0.1:33560/kdrive",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                null
            );
        });
    }

    @Test
    void missingOrMismatchedSchemaPrefixFailsClosed() {
        // Missing required schema prefix env var
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                VALID_ISOLATED_URL,
                null,
                VALID_TOKEN,
                null
            );
        });

        // Schema does not match required prefix
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                "jdbc:mysql://127.0.0.1:33560/other_database",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                null
            );
        });
    }

    @Test
    void missingMarkerTokenFailsClosed() {
        // Missing required marker token
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                VALID_ISOLATED_URL,
                VALID_SCHEMA_PREFIX,
                null,
                null
            );
        });
    }

    @Test
    void malformedOrUnverifiedProtocolsFailClosed() {
        // Arbitrary JDBC / file protocol
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                "jdbc:postgresql://127.0.0.1:5432/test",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                null
            );
        });
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                "jdbc:h2:file:/tmp/test",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                null
            );
        });
    }

    @Test
    void missingMarkerTableFailsProbeConnection() throws Exception {
        // Use an isolated in-memory DB simulating connection probe where marker table is absent
        String dbUrl = "jdbc:h2:mem:probe_test_no_marker;MODE=MySQL";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl)) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
                CancelBoundaryTest.verifyMarkerInConnection(conn, "jdbc:mysql://127.0.0.1:33562/c5_test_fixture_run", VALID_TOKEN, "PUBLIC");
            });
            assertTrue(ex.getMessage().contains("Marker table '__kdrive_test_fixture_marker' not found"));
        }
    }

    @Test
    void mismatchedMarkerTokenFailsProbeConnection() throws Exception {
        // Use an isolated in-memory DB simulating connection probe where marker table has a different token
        String dbUrl = "jdbc:h2:mem:probe_test_wrong_token;MODE=MySQL";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl)) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE __kdrive_test_fixture_marker (id INT PRIMARY KEY, marker_token VARCHAR(255))");
                stmt.execute("INSERT INTO __kdrive_test_fixture_marker VALUES (1, 'wrong_token')");
            }
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
                CancelBoundaryTest.verifyMarkerInConnection(conn, "jdbc:mysql://127.0.0.1:33562/c5_test_fixture_run", VALID_TOKEN, "PUBLIC");
            });
            assertTrue(ex.getMessage().contains("Fixture marker token mismatch"));
        }
    }

    @Test
    void missingVerifiedSchemaFailsClosedMarkerProbe() throws Exception {
        String dbUrl = "jdbc:h2:mem:probe_test_missing_schema;MODE=MySQL";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl)) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
                CancelBoundaryTest.verifyMarkerInConnection(conn, "jdbc:mysql://127.0.0.1:33562/c5_test_fixture_run", VALID_TOKEN, null);
            });
            assertTrue(ex.getMessage().contains("verifiedSchema is mandatory"));
        }
    }

    @Test
    void validMarkerTableAndTokenPassesProbeConnection() throws Exception {
        String dbUrl = "jdbc:h2:mem:probe_test_valid;MODE=MySQL";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl)) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE __kdrive_test_fixture_marker (id INT PRIMARY KEY, marker_token VARCHAR(255))");
                stmt.execute("INSERT INTO __kdrive_test_fixture_marker VALUES (1, '" + VALID_TOKEN + "')");
            }
            assertDoesNotThrow(() -> {
                CancelBoundaryTest.verifyMarkerInConnection(conn, "jdbc:mysql://127.0.0.1:33562/c5_test_fixture_run", VALID_TOKEN, "PUBLIC");
            });
        }
    }

    @Test
    void expectedTargetMatchingRejectsMismatchHostPortSchema() {
        // Mismatched expected host
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifyUrlStructure(
                "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                "10.0.0.1",
                "33560",
                "c5_test_fixture_run1"
            );
        });

        // Mismatched expected port
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifyUrlStructure(
                "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                "127.0.0.1",
                "33569",
                "c5_test_fixture_run1"
            );
        });

        // Mismatched expected schema
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifyUrlStructure(
                "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                "127.0.0.1",
                "33560",
                "c5_test_fixture_run2"
            );
        });

        // Missing expected host
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifyUrlStructure(
                "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                null,
                "33560",
                "c5_test_fixture_run1"
            );
        });

        // Missing expected port
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifyUrlStructure(
                "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                "127.0.0.1",
                "",
                "c5_test_fixture_run1"
            );
        });

        // Missing expected schema
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifyUrlStructure(
                "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                "127.0.0.1",
                "33560",
                " "
            );
        });

        // Out of range port (negative, 0, or > 65535)
        assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifyUrlStructure(
                "jdbc:mysql://127.0.0.1:70000/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                "127.0.0.1",
                "70000",
                "c5_test_fixture_run1"
            );
        });

        // Matching expected target passes
        assertDoesNotThrow(() -> {
            CancelBoundaryTest.verifyUrlStructure(
                "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                "127.0.0.1",
                "33560",
                "c5_test_fixture_run1"
            );
        });
    }

    @Test
    void connectionMetadataCatalogMismatchOrMissingIsRejected() throws Exception {
        String dbUrl = "jdbc:h2:mem:probe_test_catalog;MODE=MySQL";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl)) {
            // Mismatch schema
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
                CancelBoundaryTest.verifyConnectionMetadata(conn, "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1", "c5_test_fixture_run1");
            });
            assertTrue(ex.getMessage().contains("catalog/schema"));

            // Missing expected schema parameter fails closed
            assertThrows(IllegalStateException.class, () -> {
                CancelBoundaryTest.verifyConnectionMetadata(conn, "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1", null);
            });
        }
    }

    @Test
    void actualSchemaNullOrBlankFailsClosed() throws Exception {
        // Mock connection returning null for getCatalog and getSchema and empty database
        java.sql.Connection mockConn = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{java.sql.Connection.class},
            (proxy, method, args) -> {
                if ("getCatalog".equals(method.getName()) || "getSchema".equals(method.getName())) {
                    return null;
                }
                if ("createStatement".equals(method.getName())) {
                    // return mock statement returning empty result for SELECT DATABASE()
                    return java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Statement.class},
                        (sProxy, sMethod, sArgs) -> {
                            if ("executeQuery".equals(sMethod.getName())) {
                                return java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[]{java.sql.ResultSet.class},
                                    (rsProxy, rsMethod, rsArgs) -> {
                                        if ("next".equals(rsMethod.getName())) return false;
                                        if ("close".equals(rsMethod.getName())) return null;
                                        return null;
                                    }
                                );
                            }
                            if ("close".equals(sMethod.getName())) return null;
                            return null;
                        }
                    );
                }
                if ("close".equals(method.getName())) return null;
                return null;
            }
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifyConnectionMetadata(mockConn, "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1", "c5_test_fixture_run1");
        });
        assertTrue(ex.getMessage().contains("Actual database catalog/schema cannot be determined"));
    }

    @Test
    void actualProbePathEnforcesReadOnlyFirstAndClosesConnection() throws Exception {
        // Track the exact call sequence on the connection during probe execution
        java.util.List<String> callLog = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        java.sql.Connection mockConn = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{java.sql.Connection.class},
            (proxy, method, args) -> {
                String name = method.getName();
                callLog.add(name);
                if ("setReadOnly".equals(name)) {
                    Boolean val = (Boolean) args[0];
                    assertTrue(val, "setReadOnly must be called with true");
                    return null;
                }
                if ("getCatalog".equals(name)) {
                    return "c5_test_fixture_probe_ord";
                }
                if ("getSchema".equals(name)) {
                    return "c5_test_fixture_probe_ord";
                }
                if ("prepareStatement".equals(name)) {
                    // PreparedStatement mock returning success for count and token query
                    return java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{java.sql.PreparedStatement.class},
                        (psProxy, psMethod, psArgs) -> {
                            String psName = psMethod.getName();
                            if ("executeQuery".equals(psName)) {
                                return java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[]{java.sql.ResultSet.class},
                                    (rsProxy, rsMethod, rsArgs) -> {
                                        if ("next".equals(rsMethod.getName())) return true;
                                        if ("getInt".equals(rsMethod.getName())) return 1;
                                        if ("getString".equals(rsMethod.getName())) return VALID_TOKEN;
                                        if ("close".equals(rsMethod.getName())) return null;
                                        return null;
                                    }
                                );
                            }
                            if ("close".equals(psName)) return null;
                            return null;
                        }
                    );
                }
                if ("close".equals(name)) {
                    closed.set(true);
                    return null;
                }
                return null;
            }
        );

        // Execute actual probe path through verifySafeIsolatedDatabaseWithSupplier
        CancelBoundaryTest.verifySafeIsolatedDatabaseWithSupplier(
            "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_probe_ord",
            VALID_SCHEMA_PREFIX,
            VALID_TOKEN,
            "root",
            "password",
            "127.0.0.1",
            "33560",
            "c5_test_fixture_probe_ord",
            (url, props) -> mockConn
        );

        // Verify ordering: setReadOnly was called FIRST before getCatalog, prepareStatement, or any query
        assertFalse(callLog.isEmpty());
        assertEquals("setReadOnly", callLog.get(0), "conn.setReadOnly(true) must be the very first call on the connection");
        assertTrue(closed.get(), "Connection must be closed after probe verification");
    }

    @Test
    void probeRejectionClosesConnectionWithoutApplicationWrite() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        java.sql.Connection mockConn = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{java.sql.Connection.class},
            (proxy, method, args) -> {
                if ("setReadOnly".equals(method.getName())) return null;
                if ("getCatalog".equals(method.getName())) return "wrong_schema";
                if ("close".equals(method.getName())) {
                    closed.set(true);
                    return null;
                }
                return null;
            }
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabaseWithSupplier(
                "jdbc:mysql://127.0.0.1:33560/c5_test_fixture_run1",
                VALID_SCHEMA_PREFIX,
                VALID_TOKEN,
                "root",
                "password",
                "127.0.0.1",
                "33560",
                "c5_test_fixture_run1",
                (url, props) -> mockConn
            );
        });

        assertTrue(ex.getMessage().contains("Actual connection catalog/schema"));
        assertTrue(closed.get(), "Connection must be closed even when probe verification fails");
    }

    @Test
    void liveProbeFailsClosedWhenMarkerMissingOnLiveMySQL() {
        String testUrl = System.getenv("KDRIVE_TEST_DB_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(
            testUrl != null && testUrl.startsWith("jdbc:mysql://"),
            "Skipping live MySQL probe test: KDRIVE_TEST_DB_URL is not set to a live MySQL instance."
        );

        String prefix = System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX");
        String token = System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN");
        String expectedHost = System.getenv("KDRIVE_TEST_EXPECTED_HOST");
        String expectedPort = System.getenv("KDRIVE_TEST_EXPECTED_PORT");
        String expectedSchema = System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA");
        String user = System.getenv("KDRIVE_TEST_DB_USER") != null ? System.getenv("KDRIVE_TEST_DB_USER") : System.getenv("SPRING_DATASOURCE_USERNAME");
        String pass = System.getenv("KDRIVE_TEST_DB_PASSWORD") != null ? System.getenv("KDRIVE_TEST_DB_PASSWORD") : System.getenv("SPRING_DATASOURCE_PASSWORD");

        // Case 1: Missing marker table probe rejection
        IllegalStateException exMissing = assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                testUrl, prefix, token, user, pass,
                expectedHost, expectedPort, expectedSchema
            );
        });
        assertTrue(exMissing.getMessage().contains("Marker table '__kdrive_test_fixture_marker' not found"),
                "Expected missing marker message, got: " + exMissing.getMessage());
    }

    @Test
    void liveProbeFailsClosedWhenMarkerTokenMismatchedOnLiveMySQL() {
        String testUrl = System.getenv("KDRIVE_TEST_DB_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(
            testUrl != null && testUrl.startsWith("jdbc:mysql://"),
            "Skipping live MySQL probe test: KDRIVE_TEST_DB_URL is not set to a live MySQL instance."
        );

        String prefix = System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX");
        String expectedHost = System.getenv("KDRIVE_TEST_EXPECTED_HOST");
        String expectedPort = System.getenv("KDRIVE_TEST_EXPECTED_PORT");
        String expectedSchema = System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA");
        String user = System.getenv("KDRIVE_TEST_DB_USER") != null ? System.getenv("KDRIVE_TEST_DB_USER") : System.getenv("SPRING_DATASOURCE_USERNAME");
        String pass = System.getenv("KDRIVE_TEST_DB_PASSWORD") != null ? System.getenv("KDRIVE_TEST_DB_PASSWORD") : System.getenv("SPRING_DATASOURCE_PASSWORD");

        // Case 2: Mismatched marker token probe rejection
        IllegalStateException exWrongToken = assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                testUrl, prefix, "wrong_token_mismatched_live", user, pass,
                expectedHost, expectedPort, expectedSchema
            );
        });
        assertTrue(exWrongToken.getMessage().contains("Fixture marker token mismatch"),
                "Expected token mismatch message, got: " + exWrongToken.getMessage());
    }

    @Test
    void liveProbeFailsClosedWhenActualSchemaMismatchedOnLiveMySQL() {
        String testUrl = System.getenv("KDRIVE_TEST_DB_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(
            testUrl != null && testUrl.startsWith("jdbc:mysql://"),
            "Skipping live MySQL probe test: KDRIVE_TEST_DB_URL is not set to a live MySQL instance."
        );

        String prefix = System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX");
        String token = System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN");
        String expectedHost = System.getenv("KDRIVE_TEST_EXPECTED_HOST");
        String expectedPort = System.getenv("KDRIVE_TEST_EXPECTED_PORT");
        String user = System.getenv("KDRIVE_TEST_DB_USER") != null ? System.getenv("KDRIVE_TEST_DB_USER") : System.getenv("SPRING_DATASOURCE_USERNAME");
        String pass = System.getenv("KDRIVE_TEST_DB_PASSWORD") != null ? System.getenv("KDRIVE_TEST_DB_PASSWORD") : System.getenv("SPRING_DATASOURCE_PASSWORD");

        // Case 3: Expected schema does not match actual connected database
        IllegalStateException exSchema = assertThrows(IllegalStateException.class, () -> {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                testUrl, prefix, token, user, pass,
                expectedHost, expectedPort, "c5_test_fixture_mismatched_schema"
            );
        });
        assertTrue(exSchema.getMessage().contains("does not match expected schema") || exSchema.getMessage().contains("Actual connection catalog/schema"),
                "Expected schema mismatch message, got: " + exSchema.getMessage());
    }
}
