package com.carrental.modules.booking.service;

import com.carrental.modules.booking.repository.ReturnSettlementRepository;
import com.carrental.modules.booking.repository.ReturnSettlementRepository.Entry;
import com.carrental.security.Actor;
import com.carrental.exception.DomainException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Ledger only; BookingService owns authorization, locking, version and transaction. */
@Service
public class ReturnSettlementService {
    public record Input(String kind, java.math.BigDecimal amountVnd, String method, String reference, String reason,
                        String reversesId, String idempotencyKey, long expectedVersion, boolean confirmed) {}
    public record Summary(long originalAmount, long receivedAmount, long waivedAmount, long outstandingAmount,
                          String status, boolean demo, List<Entry> entries) {}
    private final ReturnSettlementRepository repo;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Value("${app.return-settlement-demo:true}") private boolean demo;
    public ReturnSettlementService(ReturnSettlementRepository repo, Clock clock) { this.repo=repo; this.clock=clock; }
    public Summary summary(String id, ReturnPolicy.Assessment assessment) {
        if (assessment == null) return null;
        var entries=repo.list(id);
        var reversed=new HashSet<String>();
        for(var e:entries) if(e.reversesId()!=null) reversed.add(e.reversesId());
        long received=0,waived=0;
        for(var e:entries) if(!reversed.contains(e.id())) {
            if("RECEIPT".equals(e.kind())) received=Math.addExact(received,e.amount());
            if("WAIVER".equals(e.kind())) waived=Math.addExact(waived,e.amount());
        }
        long remaining=Math.subtractExact(Math.subtractExact(assessment.amountVnd(),received),waived);
        if(remaining<0) throw new IllegalStateException("Return ledger exceeds assessment");
        String status=assessment.amountVnd()==0?"NOT_DUE":remaining>0?(received+waived>0?"PARTIAL":"UNPAID"):
            waived==assessment.amountVnd()?"WAIVED":waived>0?"SETTLED":"PAID";
        return new Summary(assessment.amountVnd(),received,waived,remaining,status,entries.isEmpty()?demo:entries.get(0).demo(),entries);
    }
    public boolean duplicate(Actor actor, Summary s, Input input) {
        for(var e:s.entries()) if(e.idempotencyKey().equals(input.idempotencyKey())) {
            if(!e.actorId().equals(actor.id()) || !e.kind().equals(input.kind()) || !input.confirmed()
                || !Objects.equals(e.reason(),input.reason()) || !Objects.equals(e.method(),input.method())
                || !Objects.equals(e.reference(),input.reference()) || !Objects.equals(e.reversesId(),input.reversesId())
                || (!"REVERSAL".equals(e.kind()) && e.amount()!=amount(input.amountVnd()))
                || ("REVERSAL".equals(e.kind()) && input.amountVnd()!=null))
                throw DomainException.conflict("Mã thao tác đã dùng cho nội dung khác. Tải lại để kiểm tra.");
            return true;
        }
        return false;
    }
    public void append(Actor actor, String id, Summary s, Input input) {
        if(!input.confirmed()) throw DomainException.bad("Cần xác nhận nội dung ghi nhận.");
        required(input.idempotencyKey(),80,"mã thao tác"); required(input.reason(),1000,"lý do/ghi chú");
        if(!s.entries().isEmpty() && s.demo()!=demo) throw DomainException.conflict("Chế độ thu đã thay đổi; cần đối soát hồ sơ.");
        long amount;
        if("REVERSAL".equals(input.kind())) {
            if(input.amountVnd()!=null || input.method()!=null || input.reference()!=null) throw DomainException.bad("Số tiền đảo do hệ thống xác định.");
            var original=s.entries().stream().filter(e->e.id().equals(input.reversesId())).findFirst().orElseThrow(DomainException::missing);
            if("REVERSAL".equals(original.kind()) || s.entries().stream().anyMatch(e->original.id().equals(e.reversesId())))
                throw DomainException.conflict("Bản ghi không thể đảo hoặc đã được đảo.");
            amount=original.amount();
        } else {
            if(input.reversesId()!=null || input.amountVnd()==null || amount(input.amountVnd())<=0) throw DomainException.bad("Số tiền phải là VND nguyên dương.");
            amount=amount(input.amountVnd());
            if(amount>s.outstandingAmount()) throw DomainException.conflict("Số tiền vượt khoản còn phải thu. Tải lại đơn.");
            if("RECEIPT".equals(input.kind())) {
                if(!Set.of("CASH","BANK_TRANSFER").contains(Objects.toString(input.method(),""))) throw DomainException.bad("Chọn phương thức thu.");
                required(input.reference(),120,"tham chiếu phiếu thu/giao dịch");
                if(s.entries().stream().anyMatch(e->"RECEIPT".equals(e.kind()) && input.reference().equals(e.reference()) && s.entries().stream().noneMatch(x->e.id().equals(x.reversesId()))))
                    throw DomainException.conflict("Tham chiếu khoản thu đang tồn tại trong đơn.");
            } else if(input.method()!=null || input.reference()!=null) throw DomainException.bad("Miễn giảm không phải khoản thu.");
        }
        repo.insert(new Entry(UUID.randomUUID().toString(),id,input.kind(),amount,actor.id(),actor.fullName(),
            clock.instant().truncatedTo(ChronoUnit.MICROS),input.method(),input.reference(),input.reason(),input.reversesId(),input.idempotencyKey(),demo));
    }
    private long amount(java.math.BigDecimal value) {
        try { if(value==null) throw new ArithmeticException(); return value.longValueExact(); }
        catch(ArithmeticException e) { throw DomainException.bad("Số tiền phải là VND nguyên trong giới hạn cho phép."); }
    }
    private void required(String value,int max,String label) {
        if(value==null || value.isBlank() || value.length()>max || !value.equals(value.trim())) throw DomainException.bad("Kiểm tra "+label+" (không để trống hoặc thêm khoảng trắng đầu/cuối).");
    }
}
