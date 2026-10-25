package com.carrental;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelBoundaryFlywayHistoryTest {

    @Test
    void appliedFlywayChecksumsMatchTheFinalMigrationResources() {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        Assumptions.assumeTrue(
            url != null && url.startsWith("jdbc:mysql://"),
            "Skipping Flyway history validation: KDRIVE_TEST_DB_URL is not set to a live MySQL fixture."
        );

        String schema = System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA");
        Flyway flyway = Flyway.configure()
            .dataSource(url, System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"))
            .defaultSchema(schema)
            .schemas(schema)
            .load();

        flyway.validate();
        Map<String, MigrationInfo> applied = Arrays.stream(flyway.info().applied())
            .filter(migration -> migration.getVersion() != null)
            .collect(Collectors.toMap(
                migration -> migration.getVersion().getVersion(),
                Function.identity()
            ));

        assertEquals(Set.of("1", "2", "3"), applied.keySet(), "Expected final V1, V2 and V3 migration records.");
        assertMigration(applied.get("1"), "rental schema");
        assertMigration(applied.get("2"), "incident audit and review vehicle");
        assertMigration(applied.get("3"), "vehicle creation time");
    }

    private static void assertMigration(MigrationInfo migration, String expectedDescription) {
        assertNotNull(migration);
        assertEquals(expectedDescription, migration.getDescription());
        assertEquals(MigrationState.SUCCESS, migration.getState());
        assertNotNull(migration.getChecksum(), "Flyway must persist a checksum for each applied SQL migration.");
        assertTrue(migration.getChecksum() != 0, "Flyway migration checksum must be a populated value.");
    }
}
