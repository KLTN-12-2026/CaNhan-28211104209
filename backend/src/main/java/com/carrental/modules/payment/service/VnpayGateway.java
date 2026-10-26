package com.carrental.modules.payment.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.payment.repository.PaymentRepository.Payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class VnpayGateway {
    public static final String CHECKOUT="https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    public static final URI API=URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private final PaymentSettings settings;private final ObjectMapper json;
    public VnpayGateway(PaymentSettings settings,ObjectMapper json){this.settings=settings;this.json=json;}
    public static String date(Instant instant){return DATE.format(instant);}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    public static String canonical(Map<String,String> values){
        return values.entrySet().stream().filter(e->e.getKey().startsWith("vnp_")&&!Set.of("vnp_SecureHash","vnp_SecureHashType").contains(e.getKey())&&e.getValue()!=null&&!e.getValue().isEmpty())
            .sorted(Map.Entry.comparingByKey()).map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
    }
    public String sign(String data){
        try{var mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec(settings.secret.getBytes(StandardCharsets.UTF_8),"HmacSHA512"));return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException e){throw new IllegalStateException("Không tạo được chữ ký VNPAY.");}
    }
    private boolean matches(String data,String signature){return signature!=null&&signature.matches("(?i)[0-9a-f]{128}")&&MessageDigest.isEqual(sign(data).getBytes(StandardCharsets.US_ASCII),signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));}
    public String checkout(Payment payment,String ip){
        settings.requireNew();var p=base("pay");p.put("vnp_Amount",Long.toString(Math.multiplyExact(payment.amount(),100)));p.put("vnp_CurrCode","VND");p.put("vnp_TxnRef",payment.id());
        p.put("vnp_OrderInfo","Booking deposit "+payment.id());p.put("vnp_OrderType","other");p.put("vnp_Locale","vn");p.put("vnp_ReturnUrl",settings.returnUrl);
        p.put("vnp_CreateDate",date(payment.createdAt()));p.put("vnp_ExpireDate",date(payment.expiresAt()));p.put("vnp_IpAddr",ip);
        String query=canonical(p);return CHECKOUT+"?"+query+"&vnp_SecureHash="+sign(query);
    }
    private TreeMap<String,String> base(String command){var p=new TreeMap<String,String>();p.put("vnp_Version","2.1.0");p.put("vnp_Command",command);p.put("vnp_TmnCode",settings.merchant);return p;}
    public record Receipt(String ref,long amount,String transaction,String payDate,boolean success,boolean definitiveFailure){
        public Receipt(String ref,long amount,String transaction,String payDate,boolean success){this(ref,amount,transaction,payDate,success,!success);}
    }
    public Receipt notification(Map<String,String> fields){
        settings.requireConfigured();if(!matches(canonical(fields),fields.get("vnp_SecureHash")))throw DomainException.bad("Chữ ký VNPAY không hợp lệ.");
        return receipt(fields);
    }
    private Receipt receipt(Map<String,String> p){
        if(!settings.merchant.equals(p.get("vnp_TmnCode"))||!p.getOrDefault("vnp_TxnRef","").matches("[a-f0-9]{32}"))throw DomainException.bad("Merchant hoặc tham chiếu không hợp lệ.");
        try{
            long encoded=Long.parseLong(p.getOrDefault("vnp_Amount",""));if(encoded<=0||encoded%100!=0)throw new NumberFormatException();
            boolean success="00".equals(p.get("vnp_ResponseCode"))&&"00".equals(p.get("vnp_TransactionStatus"));
            String number=p.get("vnp_TransactionNo"),paid=p.get("vnp_PayDate");
            if(success&&(number==null||!number.matches("[0-9]{1,30}")||paid==null||!paid.matches("[0-9]{14}")))throw new NumberFormatException();
            return new Receipt(p.get("vnp_TxnRef"),encoded/100,success?number:null,success?paid:null,success,"02".equals(p.get("vnp_TransactionStatus")));
        }catch(NumberFormatException e){throw DomainException.bad("Số tiền hoặc dữ liệu giao dịch VNPAY không hợp lệ.");}
    }
    private String joined(Map<String,String> p,String...fields){return Arrays.stream(fields).map(f->p.getOrDefault(f,"")).collect(java.util.stream.Collectors.joining("|"));}
    private static final String[] RESPONSE={"vnp_ResponseId","vnp_Command","vnp_ResponseCode","vnp_Message","vnp_TmnCode","vnp_TxnRef","vnp_Amount","vnp_BankCode","vnp_PayDate","vnp_TransactionNo","vnp_TransactionType","vnp_TransactionStatus","vnp_OrderInfo"};
    public boolean authenticResponse(Map<String,String> response,String command,String ref){
        String data=joined(response,RESPONSE);
        if("querydr".equals(command))data+="|"+response.getOrDefault("vnp_PromotionCode","")+"|"+response.getOrDefault("vnp_PromotionAmount","");
        return matches(data,response.get("vnp_SecureHash"))&&command.equals(response.get("vnp_Command"))&&settings.merchant.equals(response.get("vnp_TmnCode"))&&ref.equals(response.get("vnp_TxnRef"));
    }
    private Map<String,String> api(Map<String,String> data,String command){
        settings.requireConfigured();
        try{
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
            var request=HttpRequest.newBuilder(API).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200||response.body().length()>32768)throw new IllegalStateException("VNPAY chưa trả kết quả hợp lệ.");
            var parsed=json.readTree(response.body());var p=new TreeMap<String,String>();parsed.fields().forEachRemaining(e->p.put(e.getKey(),e.getValue().asText()));
            if(!authenticResponse(p,command,data.get("vnp_TxnRef")))throw new IllegalStateException("Không xác minh được phản hồi VNPAY.");
            return p;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Đối soát VNPAY bị gián đoạn.");}
        catch(java.io.IOException e){throw new IllegalStateException("Chưa xác minh được kết quả VNPAY.");}
    }
    private Map<String,String> queryResponse(Payment p,Instant now){
        var data=base("querydr");data.put("vnp_RequestId",UUID.randomUUID().toString().replace("-",""));data.put("vnp_TxnRef",p.id());data.put("vnp_TransactionDate",date(p.createdAt()));data.put("vnp_CreateDate",date(now));data.put("vnp_IpAddr","127.0.0.1");data.put("vnp_OrderInfo","Query deposit "+p.id());
        data.put("vnp_SecureHash",sign(joined(data,"vnp_RequestId","vnp_Version","vnp_Command","vnp_TmnCode","vnp_TxnRef","vnp_TransactionDate","vnp_CreateDate","vnp_IpAddr","vnp_OrderInfo")));
        return api(data,"querydr");
    }
    public Receipt query(Payment p,Instant now){
        var response=queryResponse(p,now);
        if(!"00".equals(response.get("vnp_ResponseCode"))||!Set.of("00","02").contains(response.getOrDefault("vnp_TransactionStatus",""))||!"01".equals(response.get("vnp_TransactionType")))throw new IllegalStateException("Giao dịch vẫn cần đối soát.");
        return receipt(response);
    }
    public record RefundReply(boolean completed,boolean rejected,String transaction){}
    public RefundReply queryRefund(Payment p,long amount,String requestDate,Instant now){
        var r=queryResponse(p,now);
        if(!"00".equals(r.get("vnp_ResponseCode"))||!(amount==p.amount()?"02":"03").equals(r.get("vnp_TransactionType"))||!Long.toString(Math.multiplyExact(amount,100)).equals(r.get("vnp_Amount")))throw new IllegalStateException("Chưa tìm được kết quả đúng khoản hoàn; cần đối soát merchant.");
        String paid=r.get("vnp_PayDate"),number=r.get("vnp_TransactionNo");
        if(requestDate==null||paid==null||!paid.matches("[0-9]{14}")||paid.compareTo(requestDate)<0||number==null||!number.matches("[0-9]{1,30}"))throw new IllegalStateException("Chưa xác định được tham chiếu khoản hoàn.");
        return new RefundReply("00".equals(r.get("vnp_TransactionStatus")),"09".equals(r.get("vnp_TransactionStatus")),number);
    }
    public RefundReply refund(Payment p,String requestId,long amount,String requestDate){
        var data=base("refund");data.put("vnp_RequestId",requestId);data.put("vnp_TransactionType",amount==p.amount()?"02":"03");data.put("vnp_TxnRef",p.id());data.put("vnp_Amount",Long.toString(Math.multiplyExact(amount,100)));data.put("vnp_TransactionNo",p.providerTransaction());data.put("vnp_TransactionDate",p.providerPayDate());data.put("vnp_CreateBy","KDriveAdmin");data.put("vnp_CreateDate",requestDate);data.put("vnp_IpAddr","127.0.0.1");data.put("vnp_OrderInfo","Refund deposit "+p.id());
        data.put("vnp_SecureHash",sign(joined(data,"vnp_RequestId","vnp_Version","vnp_Command","vnp_TmnCode","vnp_TransactionType","vnp_TxnRef","vnp_Amount","vnp_TransactionNo","vnp_TransactionDate","vnp_CreateBy","vnp_CreateDate","vnp_IpAddr","vnp_OrderInfo")));
        var r=api(data,"refund");
        if(!Long.toString(Math.multiplyExact(amount,100)).equals(r.get("vnp_Amount"))||!data.get("vnp_TransactionType").equals(r.get("vnp_TransactionType")))throw new IllegalStateException("Số tiền/loại hoàn không khớp.");
        return new RefundReply("00".equals(r.get("vnp_ResponseCode"))&&"00".equals(r.get("vnp_TransactionStatus")),"09".equals(r.get("vnp_TransactionStatus")),r.get("vnp_TransactionNo"));
    }
}
