package com.carrental.modules.operation.service;

import com.carrental.modules.payment.repository.PaymentRepository;
import com.carrental.modules.payment.service.PaymentSettings;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
public class DepositReconciliation {
    private final PaymentSettings settings;private final PaymentRepository repo;private final DepositWorkflow workflow;
    public DepositReconciliation(PaymentSettings settings,PaymentRepository repo,DepositWorkflow workflow){this.settings=settings;this.repo=repo;this.workflow=workflow;}
    @Scheduled(fixedDelayString="${app.payment-reconcile-ms:60000}",initialDelayString="${app.payment-reconcile-ms:60000}")
    public void run(){
        // Disabling new checkout must not disable recovery of existing money.
        for(String id:repo.qrBookingIds())try{workflow.settleQr(id,null);}catch(RuntimeException e){/* Visible for reconciliation. */}
        if(!settings.configured())return;
        for(var p:repo.unresolvedPayments())try{workflow.reconcile(p.id());}catch(RuntimeException e){/* Uncertainty remains visible to Admin. */}
        for(var r:repo.readyRefunds())try{workflow.processRefund(r.id());}catch(RuntimeException e){/* PROCESSING/UNKNOWN is never resent. */}
    }
}
