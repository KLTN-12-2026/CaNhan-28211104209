package com.carrental.modules.ai.parser;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.RentalBranchCatalog;
import com.carrental.security.Actor;
import java.text.Normalizer;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Extract a draft only. A customer must review it before requesting authoritative recommendations. */
@Service
public class CriteriaExtractionService {
    private final ObjectProvider<QwenCriteriaParser> parser;
    private final RentalBranchCatalog branches;
    public CriteriaExtractionService(ObjectProvider<QwenCriteriaParser> parser,RentalBranchCatalog branches){this.parser=parser;this.branches=branches;}
    public record Draft(String pickupDate,String returnDate,String pickupBranchId,String returnBranchId,Integer passengers,Long budgetVndTotal,String transmission) {}
    public record Result(String status,Draft draft,List<String> unresolvedFields) {}
    public Result extract(Actor actor,String text){
        actor.require("USER");
        if(text==null||text.isBlank()||text.length()>1000)throw DomainException.bad("Nhập nhu cầu từ1 đến1000 ký tự.");
        // Refuse common sensitive identifiers before inference; no transcript or user account is forwarded.
        if(text.matches("(?s).*[^\\s@]+@[^\\s@]+\\.[^\\s@]+.*") || text.matches("(?s).*(?<!\\d)(?:0\\d{9}|84\\d{9}|\\d{12,16})(?!\\d).*"))
            throw DomainException.bad("Vui lòng bỏ email, số điện thoại hoặc số giấy tờ khỏi nhu cầu thuê xe.");
        var engine=parser.getIfAvailable();
        if(engine==null)throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa bật đọc nhu cầu bằng Qwen; hãy dùng biểu mẫu.");
        var extracted=engine.parse(text);
        if("OUT_OF_SCOPE".equals(extracted.intent()))return new Result("OUT_OF_SCOPE",null,List.of());
        if(!extracted.conflicts().isEmpty())return new Result("NEEDS_CLARIFICATION",null,List.of("conflicts"));
        var active=branches.active();
        String pickup=resolve(extracted.pickupBranchName(),active),destination=resolve(extracted.returnBranchName(),active);
        String start=explicitDate(extracted.pickupDate(),text),end=explicitDate(extracted.returnDate(),text);
        var missing=new LinkedHashSet<>(extracted.unresolvedFields());missing.remove("transmission");
        if(start==null)missing.add("pickupDate");if(end==null)missing.add("returnDate");
        if(pickup==null)missing.add("pickupBranchName");if(destination==null)missing.add("returnBranchName");
        if(extracted.passengers()==null)missing.add("passengers");if(extracted.budgetVndTotal()==null)missing.add("budgetVndTotal");
        return new Result(missing.isEmpty()?"READY_FOR_REVIEW":"NEEDS_CLARIFICATION",
            new Draft(start,end,pickup,destination,extracted.passengers(),extracted.budgetVndTotal(),explicitTransmission(extracted.transmission(),text)),List.copyOf(missing));
    }
    private static String explicitDate(String date,String text){
        if(date==null)return null;
        var day=java.time.LocalDate.parse(date);
        String d=(day.getDayOfMonth()<10?"0?":"")+day.getDayOfMonth(),m=(day.getMonthValue()<10?"0?":"")+day.getMonthValue();
        String form="(?<![0-9])(?:"+java.util.regex.Pattern.quote(date)+"|"+d+"\\s*[/.-]\\s*"+m+"\\s*[/.-]\\s*"+day.getYear()+")(?![0-9])";
        return java.util.regex.Pattern.compile(form).matcher(text).find()?date:null;
    }
    private static String explicitTransmission(String selected,String text){
        String normalized=normalize(text);
        boolean automatic=normalized.contains("tudong")||normalized.contains("automatic"),manual=normalized.contains("sosan")||normalized.contains("sotay")||normalized.contains("manual");
        if(normalized.contains("khong")||automatic==manual)return null;
        return Objects.equals(selected,automatic?"AUTO":"MANUAL")?selected:null;
    }
    private static String resolve(String name,List<RentalBranchCatalog.Item> active){
        if(name==null)return null;
        var matches=active.stream().filter(b->normalize(b.name()).equals(normalize(name))).toList();
        return matches.size()==1?matches.get(0).id():null;
    }
    private static String normalize(String name){return Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('đ','d').replaceFirst("^kdrive\\s*","").replaceAll("[^a-z0-9]","");}
}
