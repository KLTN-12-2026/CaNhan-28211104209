package com.carrental.modules.payment.service;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.carrental.modules.booking.service.QrReconciliationReader;
import java.util.*;

/** Payment-owned, read-only projection. Caller must authorize every booking ID first. */
@Service
public class QrReconciliationReadService implements QrReconciliationReader {
    private final NamedParameterJdbcTemplate jdbc;
    public QrReconciliationReadService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Map<String, Facts> read(Collection<String> authorizedIds) {
        var ids = List.copyOf(new LinkedHashSet<>(authorizedIds));
        var result = new HashMap<String, Facts>();
        for (int start = 0; start < ids.size(); start += 500) {
            var batch = ids.subList(start, Math.min(start + 500, ids.size()));
            var params = Map.of("ids", batch);
            Map<String, List<Receipt>> receipts = new HashMap<>();
            Map<String, Set<String>> refunds = new HashMap<>();
            Set<String> retries = new HashSet<>();
            jdbc.query("select booking_id,id,status,amount,applied_amount,transfer_time,verified_at from payment_transactions where booking_id in (:ids)", params,
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        var transfer = rs.getTimestamp("transfer_time"); var verified = rs.getTimestamp("verified_at");
                        receipts.computeIfAbsent(rs.getString("booking_id"), k -> new ArrayList<>()).add(new Receipt(
                                rs.getString("id"), rs.getString("status"), rs.getLong("amount"), rs.getLong("applied_amount"),
                                transfer == null ? null : transfer.toInstant(), verified == null ? null : verified.toInstant()));
                    });
            jdbc.query("select booking_id,payment_id from payment_refunds where booking_id in (:ids)", params,
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> refunds.computeIfAbsent(rs.getString("booking_id"), k -> new HashSet<>()).add(rs.getString("payment_id")));
            jdbc.query("select distinct booking_id from payment_events where kind='DEPOSIT_RETRY' and booking_id in (:ids)", params,
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> retries.add(rs.getString("booking_id")));
            for (String id : batch) result.put(id, new Facts(receipts.getOrDefault(id, List.of()), refunds.getOrDefault(id, Set.of()), retries.contains(id)));
        }
        return Map.copyOf(result);
    }
}
