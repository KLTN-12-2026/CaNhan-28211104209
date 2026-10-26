package com.carrental.modules.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.carrental.exception.DomainException;

@Component
public class PaymentSettings {
    public final boolean enabled;
    public final String merchant, secret, returnUrl;
    public final String depositMethod;
    public PaymentSettings(@Value("${app.deposit-enabled:false}") boolean enabled,
                           @Value("${app.vnpay-tmn-code:}") String merchant,
                           @Value("${app.vnpay-hash-secret:}") String secret,
                           @Value("${app.vnpay-return-url:http://localhost:5173/user/payment-return}") String returnUrl,
                           @Value("${app.deposit-method:VNPAY_SANDBOX}") String depositMethod) {
        this.enabled=enabled;this.merchant=merchant;this.secret=secret;this.returnUrl=returnUrl;this.depositMethod=depositMethod;
        var uri=java.net.URI.create(returnUrl);
        if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IllegalStateException("VNPAY return URL không hợp lệ.");
        if (enabled && "VNPAY_SANDBOX".equals(depositMethod) && !configured())
            throw new IllegalStateException("Chưa cấu hình merchant VNPAY Sandbox; không thể bật tiền cọc VNPAY.");
        if (!java.util.Set.of("DEMO_QR_MANUAL", "VNPAY_SANDBOX").contains(depositMethod))
            throw new IllegalStateException("Phương thức cọc không hợp lệ.");
    }
    public boolean configured(){return merchant.matches("[A-Za-z0-9]{8}") && secret.length()>=16;}
    public boolean isQrMethod(){return "DEMO_QR_MANUAL".equals(depositMethod);}
    public boolean isVnpayMethod(){return "VNPAY_SANDBOX".equals(depositMethod);}
    public void requireConfigured(){if(!configured())throw DomainException.conflict("VNPAY Sandbox chưa được cấu hình. Chưa phát sinh thanh toán.");}
    public void requireNew(){
        if(!enabled)throw DomainException.conflict("Đã tạm dừng tạo thanh toán mới; giao dịch cũ vẫn được đối soát.");
        if(isVnpayMethod())requireConfigured();
    }
    public boolean acceptingNew(){
        if(!enabled)return false;
        if(isVnpayMethod())return configured();
        return true; // QR DEMO always accepts new
    }
}
