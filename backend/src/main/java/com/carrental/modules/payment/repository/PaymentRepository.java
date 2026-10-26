package com.carrental.modules.payment.repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.carrental.exception.DomainException;

@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbc;
    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Payment(String id, String bookingId, long amount, String status, Instant createdAt, Instant expiresAt,
                          String providerTransaction, String providerPayDate, boolean applied, long version,
                          String verifierId, Instant verifiedAt, Instant transferTime, String transferNote,
                          String sourceAccount, long appliedAmount, String idempotencyKey, String depositMethod) {}

    public record Refund(String id, String paymentId, String bookingId, long amount, String status, String kind,
                         String reason, String approvedBy, Instant approvedAt, Instant createdAt,
                         String providerTransaction, String requestDate, long version, String depositMethod) {}

    private static Instant instant(java.sql.ResultSet r, String name) throws java.sql.SQLException {
        var t = r.getTimestamp(name); return t == null ? null : t.toInstant();
    }

    private final RowMapper<Payment> payment = (r, n) -> new Payment(
        r.getString("id"), r.getString("booking_id"), r.getLong("amount"), r.getString("status"),
        instant(r, "created_at"), instant(r, "expires_at"), r.getString("provider_transaction"),
        r.getString("provider_pay_date"), r.getBoolean("applied"), r.getLong("version"),
        r.getString("verifier_id"), instant(r, "verified_at"), instant(r, "transfer_time"),
        r.getString("transfer_note"), r.getString("source_account"), r.getLong("applied_amount"),
        r.getString("idempotency_key"),
        r.getString("deposit_method")
    );

    private final RowMapper<Refund> refund = (r, n) -> new Refund(
        r.getString("id"), r.getString("payment_id"), r.getString("booking_id"), r.getLong("amount"),
        r.getString("status"), r.getString("kind"), r.getString("reason"), r.getString("approved_by"),
        instant(r, "approved_at"), instant(r, "created_at"), r.getString("provider_transaction"),
        r.getString("request_date"), r.getLong("version"),
        r.getString("deposit_method")
    );

    public Optional<Payment> payment(String id) {
        return jdbc.query("""
            select pt.*, b.deposit_method
            from payment_transactions pt
            left join bookings b on pt.booking_id = b.id
            where pt.id=?
            """, payment, id).stream().findFirst();
    }

    public Payment lockPayment(String id) {
        return jdbc.query("""
            select pt.*, b.deposit_method
            from payment_transactions pt
            left join bookings b on pt.booking_id = b.id
            where pt.id=? for update
            """, payment, id)
            .stream().findFirst().orElseThrow(DomainException::missing);
    }

    public List<Payment> forBooking(String id) {
        return jdbc.query("""
            select pt.*, b.deposit_method
            from payment_transactions pt
            left join bookings b on pt.booking_id = b.id
            where pt.booking_id=?
            order by pt.created_at, pt.id
            """, payment, id);
    }

    public void createPayment(String id, String booking, long amount, Instant now, Instant expiry) {
        jdbc.update("insert into payment_transactions(id,booking_id,amount,status,created_at,expires_at) values(?,?,?,'PENDING',?,?)",
            id, booking, amount, Timestamp.from(now), Timestamp.from(expiry));
    }

    public void receipt(String id, String status, String number, String payDate) {
        jdbc.update("update payment_transactions set status=?,provider_transaction=?,provider_pay_date=?,version=version+1 where id=?",
            status, number, payDate, id);
    }

    public void unknown(String id) {
        jdbc.update("update payment_transactions set status='UNKNOWN',version=version+1 where id=? and status<>'SUCCEEDED'", id);
    }

    public void applied(String id) {
        jdbc.update("update payment_transactions set applied=true, applied_amount=amount, version=version+1 where id=? and applied=false", id);
    }

    public void updateAppliedAmount(String id, long appliedAmount) {
        jdbc.update("update payment_transactions set applied_amount=?,version=version+1 where id=?", appliedAmount, id);
    }

    public Optional<Refund> refundForPayment(String id) {
        return jdbc.query("""
            select pr.*, b.deposit_method
            from payment_refunds pr
            left join bookings b on pr.booking_id = b.id
            where pr.payment_id=?
            """, refund, id).stream().findFirst();
    }

    public Refund refund(String id) {
        return jdbc.query("""
            select pr.*, b.deposit_method
            from payment_refunds pr
            left join bookings b on pr.booking_id = b.id
            where pr.id=?
            """, refund, id)
            .stream().findFirst().orElseThrow(DomainException::missing);
    }

    public Refund lockRefund(String id) {
        return jdbc.query("""
            select pr.*, b.deposit_method
            from payment_refunds pr
            left join bookings b on pr.booking_id = b.id
            where pr.id=? for update
            """, refund, id)
            .stream().findFirst().orElseThrow(DomainException::missing);
    }

    public void createRefund(String id, Payment p, long amount, String status, String kind, String reason, Instant now) {
        jdbc.update("insert into payment_refunds(id,payment_id,booking_id,amount,status,kind,reason,created_at,business_key) values(?,?,?,?,?,?,?,?,?)",
            id, p.id(), p.bookingId(), amount, status, kind, reason, Timestamp.from(now), p.id()+":"+kind);
    }

    public void decide(String id, long version, long amount, String reason, String actor, Instant now) {
        int rows = jdbc.update("update payment_refunds set amount=?,status=?,reason=?,approved_by=?,approved_at=?,version=version+1 where id=? and version=? and status='REQUESTED'",
            amount, amount == 0 ? "DECLINED" : "APPROVED", reason, actor, Timestamp.from(now), id, version);
        if (rows != 1) throw DomainException.conflict("Yêu cầu hoàn đã thay đổi hoặc đã được quyết định.");
    }

    public void refundStatus(String id, String status, String provider, String requestDate) {
        jdbc.update("update payment_refunds set status=?,provider_transaction=?,request_date=?,version=version+1 where id=?",
            status, provider, requestDate, id);
    }

    public long reserved(String paymentId, String excluded) {
        return jdbc.queryForObject("select coalesce(sum(amount),0) from payment_refunds where payment_id=? and (? is null or id<>?) and status in ('APPROVED','PROCESSING','SUCCEEDED','UNKNOWN')",
            Long.class, paymentId, excluded, excluded);
    }

    public List<Refund> refunds(String booking) {
        return jdbc.query("""
            select pr.*, b.deposit_method
            from payment_refunds pr
            left join bookings b on pr.booking_id = b.id
            where pr.booking_id=?
            order by pr.created_at, pr.id
            """, refund, booking);
    }

    public List<Refund> adminRefunds() {
        return jdbc.query("""
            select pr.*, b.deposit_method
            from payment_refunds pr
            left join bookings b on pr.booking_id = b.id
            order by pr.created_at desc, pr.id limit 200
            """, refund);
    }

    public List<Payment> adminPayments() {
        return jdbc.query("""
            select pt.*, b.deposit_method
            from payment_transactions pt
            left join bookings b on pt.booking_id = b.id
            order by pt.created_at desc, pt.id limit 200
            """, payment);
    }

    public Map<String, Long> reservedAmounts() {
        Map<String, Long> amounts = new HashMap<>();
        jdbc.query("""
            select payment_id, sum(amount) from payment_refunds
            where status in ('APPROVED','PROCESSING','SUCCEEDED','UNKNOWN') group by payment_id
            """, (r, n) -> Map.entry(r.getString(1), r.getLong(2)))
            .forEach(entry -> amounts.put(entry.getKey(), entry.getValue()));
        return amounts;
    }

    public List<Payment> paymentsForRefunds(List<Refund> refunds) {
        var ids = refunds.stream().map(Refund::paymentId).distinct().toList();
        if (ids.isEmpty()) return List.of();
        return jdbc.query("select pt.*, b.deposit_method from payment_transactions pt " +
            "left join bookings b on pt.booking_id=b.id where pt.id in (" +
            String.join(",", Collections.nCopies(ids.size(), "?")) + ")", payment, ids.toArray());
    }

    public List<String> manualRefundPaymentIds() {
        return jdbc.queryForList("select distinct payment_id from payment_refunds where kind='MANUAL_EXCESS'", String.class);
    }

    public List<Payment> unresolvedPayments() {
        return jdbc.query("""
            select pt.*, b.deposit_method
            from payment_transactions pt
            left join bookings b on pt.booking_id = b.id
            where pt.status in ('PENDING','UNKNOWN') or (pt.status='SUCCEEDED' and pt.applied_amount < pt.amount and not exists(select 1 from payment_refunds r where r.payment_id=pt.id))
            order by pt.created_at, pt.id limit 50
            """, payment);
    }

    public List<Refund> readyRefunds() {
        return jdbc.query("select pr.*,b.deposit_method from payment_refunds pr join bookings b on b.id=pr.booking_id where pr.status='APPROVED' order by pr.created_at,pr.id limit 20", refund);
    }

    public List<Payment> unknownPayments() {
        return jdbc.query("""
            select pt.*, b.deposit_method
            from payment_transactions pt
            left join bookings b on pt.booking_id = b.id
            where pt.status='UNKNOWN'
            order by pt.created_at desc, pt.id limit 100
            """, payment);
    }

    public void event(String booking, String paymentId, String kind, String note, String actor, Instant now, String key) {
        if (key != null && jdbc.queryForObject("select count(*) from payment_events where event_key=?", Long.class, key) > 0) return;
        jdbc.update("insert into payment_events(id,booking_id,payment_id,kind,note,actor_id,created_at,event_key) values(?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), booking, paymentId, kind, note, actor, Timestamp.from(now), key);
    }

    public List<String> qrBookingIds() {
        return jdbc.queryForList("select distinct b.id from bookings b join payment_transactions p on p.booking_id=b.id where b.deposit_method='DEMO_QR_MANUAL' and b.status='PENDING' and p.status='SUCCEEDED'", String.class);
    }

    public Object events(String booking) {
        return jdbc.queryForList("select kind,note,actor_id,created_at from payment_events where booking_id=? order by created_at,id", booking);
    }
}
