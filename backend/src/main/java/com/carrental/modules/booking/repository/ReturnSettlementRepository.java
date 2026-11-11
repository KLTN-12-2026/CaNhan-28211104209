package com.carrental.modules.booking.repository;

import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class ReturnSettlementRepository {
    public record Entry(String id, String bookingId, String kind, long amount, String actorId, String actorName,
                        Instant createdAt, String method, String reference, String reason, String reversesId,
                        String idempotencyKey, boolean demo) {}
    private final JdbcTemplate jdbc;
    public ReturnSettlementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Entry> list(String id) {
        return jdbc.query("select * from booking_return_settlements where booking_id=? order by created_at,id",
            (r,n) -> new Entry(r.getString("id"),r.getString("booking_id"),r.getString("kind"),r.getLong("amount"),
                r.getString("actor_id"),r.getString("actor_name"),r.getTimestamp("created_at").toInstant(),
                r.getString("method"),r.getString("reference_code"),r.getString("reason"),r.getString("reverses_id"),
                r.getString("idempotency_key"),r.getBoolean("demo")), id);
    }
    public void insert(Entry e) {
        jdbc.update("insert into booking_return_settlements(id,booking_id,kind,amount,actor_id,actor_name,created_at,method,reference_code,reason,reverses_id,idempotency_key,demo) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            e.id(),e.bookingId(),e.kind(),e.amount(),e.actorId(),e.actorName(),Timestamp.from(e.createdAt()),e.method(),e.reference(),e.reason(),e.reversesId(),e.idempotencyKey(),e.demo());
    }
}
