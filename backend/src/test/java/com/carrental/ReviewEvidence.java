package com.carrental;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full fixture state: preserves update/delete side effects that counts would miss. */
final class ReviewEvidence {
    static Map<String, Object> snapshot(JdbcTemplate jdbc) {
        Map<String, Object> result = new TreeMap<>();
        // Dedicated fixture, no background writers. Include document child rows and history.
        for (String table : List.of("reviews", "bookings", "booking_status_history",
                "vehicle_handover_records", "contracts", "invoices", "invoice_items")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return result;
    }

    static void record(String label, Object before, Object after) throws Exception {
        String directory = System.getProperty("f04.evidenceDir");
        if (directory == null) return; // Ordinary local test runs still execute assertions.
        Path root = Path.of(directory);
        Files.createDirectories(root);
        new ObjectMapper().writeValue(root.resolve(label + ".json").toFile(),
                Map.of("before", before, "after", after));
    }

    static void moderation(Map<String, Object> before, Map<String, Object> after,
                           boolean hidden, String actor, java.time.Instant at) {
        Map<String, Object> expected = new LinkedHashMap<>(before);
        expected.put("hidden", hidden);
        expected.put("version", ((Number) before.get("version")).longValue() + 1);
        expected.put("moderated_by", actor);
        expected.put("moderated_at", java.sql.Timestamp.from(at));
        assertEquals(expected, after, "Only moderation fields may change");
    }
}
