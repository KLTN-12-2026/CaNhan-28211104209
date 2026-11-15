package com.carrental.modules.ai.parser;

import com.carrental.exception.DomainException;
import com.carrental.modules.ai.AiDiagnostics;
import com.carrental.modules.ai.AiRequestDeadline;
import com.carrental.modules.booking.service.RentalBranchCatalog;
import com.carrental.security.Actor;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Extract a draft only. A customer must review it before requesting authoritative recommendations. */
@Service
public class CriteriaExtractionService {
    private static final Pattern AUTOMATIC_TERMS=Pattern.compile("\\b(?:tu\\s*dong|automatic)\\b");
    private static final Pattern MANUAL_TERMS=Pattern.compile("\\b(?:so\\s*san|so\\s*tay|manual)\\b");
    private static final Pattern NEGATION_SUFFIX=Pattern.compile("(?:^|\\s)(?:khong|not)\\b.*$");
    private static final Pattern REQUEST_AFTER_CONJUNCTION=Pattern.compile(
        "\\b(?:va|and)\\s+(?=(?:toi\\s+)?(?:muon|can|thich|chon|uu\\s+tien|want|need|prefer)\\b)");
    private final ObjectProvider<QwenCriteriaParser> parser;
    private final RentalBranchCatalog branches;
    private final AiDiagnostics diagnostics;
    private final String model;
    @Autowired
    public CriteriaExtractionService(
            ObjectProvider<QwenCriteriaParser> parser,
            RentalBranchCatalog branches,
            AiDiagnostics diagnostics,
            @Value("${app.qwen-model:qwen3:8b}") String model) {
        this.parser=parser;this.branches=branches;this.diagnostics=diagnostics;this.model=model;
    }
    public CriteriaExtractionService(ObjectProvider<QwenCriteriaParser> parser,RentalBranchCatalog branches){
        this(parser,branches,new AiDiagnostics(),"qwen3:8b");
    }
    public record Draft(String pickupDate,String returnDate,String pickupBranchId,String returnBranchId,Integer passengers,Long budgetVndTotal,String transmission) {}
    public record Result(String status,Draft draft,List<String> unresolvedFields) {}
    public Result extract(Actor actor,String text){
        AiRequestDeadline deadline=AiRequestDeadline.start(AiRequestDeadline.Operation.CRITERIA_EXTRACTION);
        actor.require("USER");
        if(text==null||text.isBlank()||text.length()>1000)throw DomainException.bad("Nhập nhu cầu từ1 đến1000 ký tự.");
        // Refuse common sensitive identifiers before inference; no transcript or user account is forwarded.
        if(text.matches("(?s).*[^\\s@]+@[^\\s@]+\\.[^\\s@]+.*") || text.matches("(?s).*(?<!\\d)(?:0\\d{9}|84\\d{9}|\\d{12,16})(?!\\d).*"))
            throw DomainException.bad("Vui lòng bỏ email, số điện thoại hoặc số giấy tờ khỏi nhu cầu thuê xe.");
        var engine=parser.getIfAvailable();
        if(engine==null) {
            diagnostics.record(deadline.operation(), AiDiagnostics.Provider.QWEN, model,
                AiDiagnostics.Cause.DISABLED, deadline.elapsedNanos());
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa bật đọc nhu cầu bằng Qwen; hãy dùng biểu mẫu.");
        }
        var extracted=engine.parse(text,deadline);
        if("OUT_OF_SCOPE".equals(extracted.intent()))return new Result("OUT_OF_SCOPE",null,List.of());
        if(!extracted.conflicts().isEmpty())return new Result("NEEDS_CLARIFICATION",null,List.of("conflicts"));
        var active=branches.active();
        String pickup=resolve(extracted.pickupBranchName(),active),destination=resolve(extracted.returnBranchName(),active);
        String start=explicitDate(extracted.pickupDate(),text),end=explicitDate(extracted.returnDate(),text);
        var missing=new LinkedHashSet<>(extracted.unresolvedFields());missing.remove("transmission");
        if(start==null)missing.add("pickupDate");if(end==null)missing.add("returnDate");
        if(pickup==null)missing.add("pickupBranchName");if(destination==null)missing.add("returnBranchName");
        if(extracted.passengers()==null)missing.add("passengers");if(extracted.budgetVndTotal()==null)missing.add("budgetVndTotal");
        var transmission=explicitTransmission(extracted.transmission(),text);
        if(transmission.ambiguous())missing.add("transmission");
        return new Result(missing.isEmpty()?"READY_FOR_REVIEW":"NEEDS_CLARIFICATION",
            new Draft(start,end,pickup,destination,extracted.passengers(),extracted.budgetVndTotal(),transmission.value()),List.copyOf(missing));
    }
    private static String explicitDate(String date,String text){
        if(date==null)return null;
        var day=java.time.LocalDate.parse(date);
        String d=(day.getDayOfMonth()<10?"0?":"")+day.getDayOfMonth(),m=(day.getMonthValue()<10?"0?":"")+day.getMonthValue();
        String form="(?<![0-9])(?:"+java.util.regex.Pattern.quote(date)+"|"+d+"\\s*[/.-]\\s*"+m+"\\s*[/.-]\\s*"+day.getYear()+")(?![0-9])";
        return java.util.regex.Pattern.compile(form).matcher(text).find()?date:null;
    }
    private record TransmissionDecision(String value,boolean ambiguous) {}
    private static TransmissionDecision explicitTransmission(String selected,String text){
        String normalized=normalizeWords(text);
        boolean automatic=containsUnnegated(AUTOMATIC_TERMS,normalized);
        boolean manual=containsUnnegated(MANUAL_TERMS,normalized);
        boolean automaticNegated=containsNegated(AUTOMATIC_TERMS,normalized);
        boolean manualNegated=containsNegated(MANUAL_TERMS,normalized);
        if((automatic&&manual)||(automatic&&automaticNegated)||(manual&&manualNegated))
            return new TransmissionDecision(null,true);
        if(!automatic&&!manual)
            return new TransmissionDecision(null,automaticNegated||manualNegated);
        String explicit=automatic?"AUTO":"MANUAL";
        return Objects.equals(selected,explicit)
            ?new TransmissionDecision(selected,false):new TransmissionDecision(null,true);
    }
    private static boolean containsUnnegated(Pattern terms,String text){
        Matcher matcher=terms.matcher(text);
        while(matcher.find()){
            int clauseStart=text.lastIndexOf('|',Math.max(0,matcher.start()-1))+1;
            if(!NEGATION_SUFFIX.matcher(text.substring(clauseStart,matcher.start())).find())return true;
        }
        return false;
    }
    private static boolean containsNegated(Pattern terms,String text){
        Matcher matcher=terms.matcher(text);
        while(matcher.find()){
            int clauseStart=text.lastIndexOf('|',Math.max(0,matcher.start()-1))+1;
            if(NEGATION_SUFFIX.matcher(text.substring(clauseStart,matcher.start())).find())return true;
        }
        return false;
    }
    private static String resolve(String name,List<RentalBranchCatalog.Item> active){
        if(name==null)return null;
        var matches=active.stream().filter(b->normalize(b.name()).equals(normalize(name))).toList();
        return matches.size()==1?matches.get(0).id():null;
    }
    private static String normalize(String name){return Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('đ','d').replaceFirst("^kdrive\\s*","").replaceAll("[^a-z0-9]","");}
    private static String normalizeWords(String text){
        String normalized=Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}","")
            .toLowerCase(Locale.ROOT).replace('đ','d').replaceAll("[,.!?;:]+"," | ")
            .replaceAll("\\b(?:nhung|ma|tuy nhien)\\b"," | ");
        normalized=REQUEST_AFTER_CONJUNCTION.matcher(normalized).replaceAll(" | ");
        return normalized.replaceAll("[^a-z0-9|]+"," ").trim();
    }
}
