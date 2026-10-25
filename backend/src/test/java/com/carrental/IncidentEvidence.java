package com.carrental;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

final class IncidentEvidence {
    static Map<String, Object> snapshot(JdbcTemplate jdbc) {
        Map<String, Object> result = new TreeMap<>();
        // Bao gồm toàn bộ các bảng liên quan đến sự cố, xe, booking, lịch sử, chứng từ và bảng đại diện không liên quan (reviews)
        for (String table : List.of(
                "incident_cases",
                "incident_events",
                "vehicles",
                "bookings",
                "booking_status_history",
                "vehicle_handover_records",
                "contracts",
                "invoices",
                "invoice_items",
                "reviews")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return result;
    }

    static void ensurePopulatedRepresentativeRows(JdbcTemplate jdbc) {
        // Kiểm tra xem đã có review đại diện chưa
        Long countReviews = jdbc.queryForObject("SELECT COUNT(*) FROM reviews", Long.class);
        if (countReviews != null && countReviews > 0) {
            return;
        }

        // Tạo 1 xe và 1 booking completed độc lập cho dữ liệu mẫu
        String sampleVehId = "veh-sample-rep";
        Long vehCnt = jdbc.queryForObject("SELECT COUNT(*) FROM vehicles WHERE id=?", Long.class, sampleVehId);
        if (vehCnt == null || vehCnt == 0) {
            jdbc.update("INSERT INTO vehicles(id, name, plate, brand_id, category_id, home_branch_id, current_branch_id, seats, transmission, daily_price, status, description, version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                sampleVehId, "Xe Mẫu Giữ Dữ Liệu", "30A-99999", "toyota", "sedan", "hn", "hn", 5, "AUTO", 800000L, "ACTIVE", "Xe mẫu", 0L);
        }

        String sampleBkId = "bk-sample-rep";
        Long bkCnt = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE id=?", Long.class, sampleBkId);
        java.sql.Timestamp past = java.sql.Timestamp.valueOf("2026-09-01 08:00:00");
        java.sql.Timestamp pastStart = java.sql.Timestamp.valueOf("2026-09-01 08:00:00");
        java.sql.Timestamp pastEnd = java.sql.Timestamp.valueOf("2026-09-02 08:00:00");
        if (bkCnt == null || bkCnt == 0) {
            jdbc.update("INSERT INTO bookings(id, user_id, vehicle_id, customer_name, vehicle_name, pickup_branch_id, return_branch_id, pickup_name, return_name, actual_return_branch_id, start_at, end_at, expires_at, status, daily_price, rental_days, rental_total, one_way_fee, total, policy_version, version, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                sampleBkId, "demo-user", sampleVehId, "Khách Đại Diện", "Xe Mẫu Giữ Dữ Liệu", "hn", "hn", "hn", "hn", "hn",
                pastStart, pastEnd, pastStart, "COMPLETED", 800000L, 1, 800000L, 0L, 800000L, "rental-2026-09-28", 0L, pastStart);
        }

        // 1. contracts
        Long cntContracts = jdbc.queryForObject("SELECT COUNT(*) FROM contracts WHERE booking_id=?", Long.class, sampleBkId);
        if (cntContracts == null || cntContracts == 0) {
            jdbc.update("INSERT INTO contracts(id, booking_id, issued_at, snapshot, terms_version) VALUES(?, ?, ?, ?, ?)",
                "contract-sample-rep", sampleBkId, past, "{\"contract\":\"sample\"}", "v1.0");
        }

        // 2. invoices
        Long cntInvoices = jdbc.queryForObject("SELECT COUNT(*) FROM invoices WHERE booking_id=?", Long.class, sampleBkId);
        if (cntInvoices == null || cntInvoices == 0) {
            jdbc.update("INSERT INTO invoices(id, booking_id, issued_at, total, snapshot) VALUES(?, ?, ?, ?, ?)",
                "invoice-sample-rep", sampleBkId, past, 800000L, "{\"invoice\":\"sample\"}");
        }

        // 3. invoice_items
        Long cntItems = jdbc.queryForObject("SELECT COUNT(*) FROM invoice_items WHERE invoice_id=?", Long.class, "invoice-sample-rep");
        if (cntItems == null || cntItems == 0) {
            jdbc.update("INSERT INTO invoice_items(id, invoice_id, name, quantity, unit_price, amount) VALUES(?, ?, ?, ?, ?, ?)",
                "item-sample-rep", "invoice-sample-rep", "Cước thuê xe", 1, 800000L, 800000L);
        }

        // 4. reviews
        Long cntRev = jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE booking_id=?", Long.class, sampleBkId);
        if (cntRev == null || cntRev == 0) {
            jdbc.update("INSERT INTO reviews(id, booking_id, rating, content, hidden, created_at, moderated_by, moderated_at, version) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "review-sample-rep", sampleBkId, 5, "Dịch vụ rất tốt", false, past, null, null, 0L);
        }
    }
}
