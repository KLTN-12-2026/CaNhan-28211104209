package com.carrental.modules.ai.parser;
import com.carrental.exception.DomainException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
final class ExtractionValidation {
    private static final Set<String> FIELDS=Set.of("pickupDate","returnDate","pickupBranchName","returnBranchName","passengers","budgetVndTotal","transmission");
    static void validate(CriteriaParser.Extraction x){
        if(x==null || x.intent()==null || !Set.of("RECOMMEND_VEHICLE","NEEDS_CLARIFICATION","OUT_OF_SCOPE").contains(x.intent())
                || x.unresolvedFields()==null || x.conflicts()==null || x.unresolvedFields().size()>7 || x.conflicts().size()>7
                || x.unresolvedFields().stream().anyMatch(f->f==null||!FIELDS.contains(f))
                || x.conflicts().stream().anyMatch(f->f==null||f.isBlank()||f.length()>160)
                || x.passengers()!=null&&(x.passengers()<2||x.passengers()>9)
                || x.budgetVndTotal()!=null&&(x.budgetVndTotal()<0||x.budgetVndTotal()>9007199254740991L)
                || x.transmission()!=null&&!Set.of("AUTO","MANUAL").contains(x.transmission()))throw unavailable();
        for(String name:Arrays.asList(x.pickupBranchName(),x.returnBranchName()))if(name!=null&&(name.isBlank()||name.length()>120))throw unavailable();
        for(String date:Arrays.asList(x.pickupDate(),x.returnDate()))if(date!=null){if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw unavailable();LocalDate.parse(date);}
        if(x.pickupDate()!=null&&x.returnDate()!=null&&!LocalDate.parse(x.returnDate()).isAfter(LocalDate.parse(x.pickupDate())))throw unavailable();
    }
    private static DomainException unavailable(){return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"Kết quả đọc nhu cầu không hợp lệ; hãy dùng biểu mẫu.");}
}
