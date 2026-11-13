package com.carrental.modules.booking.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read port: implementations receive only IDs already authorized by the booking caller. */
public interface QrReconciliationReader {
    record Receipt(String id, String status, long amount, long appliedAmount, Instant transferTime, Instant verifiedAt) {}
    record Facts(List<Receipt> receipts, Set<String> refundedPaymentIds, boolean hasRetryEvent) {
        public Facts { receipts = List.copyOf(receipts); refundedPaymentIds = Set.copyOf(refundedPaymentIds); }
    }
    Map<String, Facts> read(Collection<String> authorizedIds);
}
