package com.carrental;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CancelBoundaryBootstrapNegativeTest {

    @Test
    void invalidFixtureIsRejectedDuringRealSpringBootstrap() {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        Assumptions.assumeTrue(
            url != null && url.startsWith("jdbc:mysql://"),
            "Skipping bootstrap negative test: KDRIVE_TEST_DB_URL is not set to a live MySQL fixture."
        );

        String testCase = System.getenv("KDRIVE_TEST_BOOTSTRAP_CASE");
        Class<?> contextClass;
        String expectedGuardMessage;
        switch (testCase == null ? "" : testCase) {
            case "missing-marker" -> {
                contextClass = MissingMarkerContext.class;
                expectedGuardMessage = "Marker table '__kdrive_test_fixture_marker' not found";
            }
            case "wrong-token" -> {
                contextClass = WrongTokenContext.class;
                expectedGuardMessage = "Fixture marker token mismatch";
            }
            case "target-mismatch" -> {
                contextClass = WrongTargetContext.class;
                expectedGuardMessage = "does not match expected schema";
            }
            default -> throw new IllegalArgumentException(
                "KDRIVE_TEST_BOOTSTRAP_CASE must select a supported isolated bootstrap case."
            );
        }

        ConfigurableApplicationContext context = null;
        try {
            TestContextManager manager = new TestContextManager(contextClass);
            context = (ConfigurableApplicationContext) manager.getTestContext().getApplicationContext();
            fail("Spring context unexpectedly initialized for bootstrap case " + testCase);
        } catch (RuntimeException bootstrapFailure) {
            assertNotNull(findGuardFailure(bootstrapFailure, expectedGuardMessage),
                () -> "Expected guard rejection '" + expectedGuardMessage
                    + "' during Spring bootstrap; observed: " + describeCauses(bootstrapFailure));
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static IllegalStateException findGuardFailure(Throwable failure, String expectedMessage) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalStateException guardFailure
                && guardFailure.getMessage() != null
                && guardFailure.getMessage().contains("[C5-F04 GUARD]")
                && guardFailure.getMessage().contains(expectedMessage)) {
                return guardFailure;
            }
        }
        return null;
    }

    private static String describeCauses(Throwable failure) {
        StringBuilder description = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!description.isEmpty()) {
                description.append(" <- ");
            }
            description.append(cause.getClass().getSimpleName())
                .append(": ")
                .append(cause.getMessage());
        }
        return description.toString();
    }

    private static void verifyAndRegisterDataSource(DynamicPropertyRegistry registry, String expectedSchema) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        String user = System.getenv("KDRIVE_TEST_DB_USER");
        String password = System.getenv("KDRIVE_TEST_DB_PASSWORD");
        String expectedToken = System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN");

        CancelBoundaryTest.verifySafeIsolatedDatabase(
            url,
            System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),
            expectedToken,
            user,
            password,
            System.getenv("KDRIVE_TEST_EXPECTED_HOST"),
            System.getenv("KDRIVE_TEST_EXPECTED_PORT"),
            expectedSchema
        );
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
    }

    @SpringBootTest(classes = KDriveApplication.class, properties = {
        "spring.flyway.enabled=true",
        "app.seed-enabled=true"
    })
    static class MissingMarkerContext {
        @DynamicPropertySource
        static void database(DynamicPropertyRegistry registry) {
            verifyAndRegisterDataSource(registry, System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA"));
        }
    }

    @SpringBootTest(classes = KDriveApplication.class, properties = {
        "spring.flyway.enabled=true",
        "app.seed-enabled=true"
    })
    static class WrongTokenContext {
        @DynamicPropertySource
        static void database(DynamicPropertyRegistry registry) {
            verifyAndRegisterDataSource(registry, System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA"));
        }
    }

    @SpringBootTest(classes = KDriveApplication.class, properties = {
        "spring.flyway.enabled=true",
        "app.seed-enabled=true"
    })
    static class WrongTargetContext {
        @DynamicPropertySource
        static void database(DynamicPropertyRegistry registry) {
            String expectedSchema = System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA");
            verifyAndRegisterDataSource(registry, expectedSchema + "_mismatch");
        }
    }
}
