package com.carrental;
import com.carrental.modules.payment.service.*;
import com.carrental.modules.booking.service.DepositPolicy;
import com.carrental.modules.payment.repository.PaymentRepository.Payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DepositPolicyTest {
    @Test void roundsRentalOnlyWithoutOverflow(){
        assertEquals(600000, DepositPolicy.amount(2000000)); assertEquals(1, DepositPolicy.amount(1));
        assertEquals(31, DepositPolicy.amount(101)); assertEquals(2767011611056432743L, DepositPolicy.amount(Long.MAX_VALUE));
        assertThrows(com.carrental.exception.DomainException.class, () -> DepositPolicy.amount(0));
    }
    @Test void deadlineNeverExtendsOriginalHold(){
        var now = Instant.parse("2026-10-24T03:00:00Z");
        assertEquals(now.plusSeconds(1800), DepositPolicy.paymentDueAt(now, now.plusSeconds(7200)));
        assertEquals(now.plusSeconds(5), DepositPolicy.paymentDueAt(now, now.plusSeconds(5)));
        assertTrue(DepositPolicy.early(now, now.plusSeconds(86400))); assertFalse(DepositPolicy.early(now, now.plusSeconds(86399)));
    }
    @Test void reviewDueAtNeverExtendsOriginalHold(){
        var now = Instant.parse("2026-10-24T03:00:00Z");
        var paymentDue = now.plusSeconds(1800);
        assertEquals(paymentDue.plusSeconds(1800), DepositPolicy.reviewDueAt(paymentDue, now.plusSeconds(7200)));
        assertEquals(now.plusSeconds(5), DepositPolicy.reviewDueAt(paymentDue, now.plusSeconds(5)));
    }
    @Test void disabledAndMissingConfigurationFailClosed(){
        var settings = new PaymentSettings(false, "", "", "http://localhost:5173/user/payment-return", "VNPAY_SANDBOX");
        assertFalse(settings.configured()); assertThrows(RuntimeException.class, settings::requireNew);
        assertThrows(IllegalStateException.class, () -> new PaymentSettings(true, "", "", "http://localhost:5173/user/payment-return", "VNPAY_SANDBOX"));
    }
    @Test void signedNotificationRejectsTamperingAndMerchantMismatch(){
        var g = new VnpayGateway(new PaymentSettings(true, "TESTONLY", "synthetic-secret-not-real-merchant", "http://localhost:5173/user/payment-return", "VNPAY_SANDBOX"), new ObjectMapper());
        var p = new TreeMap<String, String>(); p.put("vnp_TmnCode", "TESTONLY"); p.put("vnp_TxnRef", "a".repeat(32)); p.put("vnp_Amount", "60000000"); p.put("vnp_TransactionNo", "123456"); p.put("vnp_PayDate", "20261024103000"); p.put("vnp_ResponseCode", "00"); p.put("vnp_TransactionStatus", "00");
        p.put("vnp_SecureHash", g.sign(VnpayGateway.canonical(p))); assertTrue(g.notification(p).success()); assertEquals(600000, g.notification(p).amount());
        p.put("vnp_Amount", "60000100"); assertThrows(RuntimeException.class, () -> g.notification(p));
        p.put("vnp_TmnCode", "OTHERONE"); p.put("vnp_SecureHash", g.sign(VnpayGateway.canonical(p))); assertThrows(RuntimeException.class, () -> g.notification(p));
    }
    @Test void checkoutHasSandboxOnlyAndIntegerVndEncoding(){
        var g = new VnpayGateway(new PaymentSettings(true, "TESTONLY", "synthetic-secret-not-real-merchant", "http://localhost:5173/user/payment-return", "VNPAY_SANDBOX"), new ObjectMapper());
        var now = Instant.parse("2026-10-24T03:00:00Z");
        var url = g.checkout(new Payment("b".repeat(32), "booking", 600000, "PENDING", now, now.plusSeconds(1800), null, null, false, 0, null, null, null, null, null, 0, null, "VNPAY_SANDBOX"), "127.0.0.1");
        assertTrue(url.startsWith("https://sandbox.vnpayment.vn/")); assertTrue(url.contains("vnp_Amount=60000000")); assertTrue(url.contains("vnp_ExpireDate=20261024103000"));
        assertEquals("vnp_OrderInfo=a+b%26c", VnpayGateway.canonical(Map.of("vnp_OrderInfo", "a b&c", "vnp_SecureHash", "ignored", "other", "ignored")));
    }
    @Test void queryChecksumIncludesPromotionFieldsButRefundDoesNot(){
        var g = new VnpayGateway(new PaymentSettings(true, "TESTONLY", "synthetic-secret-not-real-merchant", "http://localhost:5173/user/payment-return", "VNPAY_SANDBOX"), new ObjectMapper());
        var fields = new HashMap<String, String>(Map.of("vnp_ResponseId", "R1", "vnp_Command", "querydr", "vnp_ResponseCode", "00", "vnp_Message", "OK", "vnp_TmnCode", "TESTONLY", "vnp_TxnRef", "a".repeat(32)));
        String base = "R1|querydr|00|OK|TESTONLY|" + "a".repeat(32) + "|||||||";
        fields.put("vnp_SecureHash", g.sign(base + "||")); assertTrue(g.authenticResponse(fields, "querydr", "a".repeat(32)));
        fields.put("vnp_SecureHash", g.sign(base)); assertFalse(g.authenticResponse(fields, "querydr", "a".repeat(32)));
        fields.put("vnp_Command", "refund"); fields.put("vnp_SecureHash", g.sign(base.replace("querydr", "refund"))); assertTrue(g.authenticResponse(fields, "refund", "a".repeat(32)));
    }
}