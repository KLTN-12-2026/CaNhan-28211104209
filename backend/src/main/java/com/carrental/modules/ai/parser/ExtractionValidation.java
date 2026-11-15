package com.carrental.modules.ai.parser;
import com.carrental.modules.ai.AiDiagnostics.SchemaField;
import com.carrental.exception.DomainException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
final class ExtractionValidation {
    private static final Set<String> FIELDS=Set.of("pickupDate","returnDate","pickupBranchName","returnBranchName","passengers","budgetVndTotal","transmission");
    static void validate(CriteriaParser.Extraction x){
        if(invalidField(x)!=SchemaField.NONE)throw unavailable();
    }
    static SchemaField invalidField(CriteriaParser.Extraction x){
        if(x==null)return SchemaField.EXTRACTION;
        if(x.intent()==null||!Set.of("RECOMMEND_VEHICLE","NEEDS_CLARIFICATION","OUT_OF_SCOPE").contains(x.intent()))return SchemaField.INTENT;
        if(x.unresolvedFields()==null||x.unresolvedFields().size()>7
                ||x.unresolvedFields().stream().anyMatch(f->f==null||!FIELDS.contains(f)))return SchemaField.UNRESOLVED_FIELDS;
        if(x.conflicts()==null||x.conflicts().size()>7
                ||x.conflicts().stream().anyMatch(f->f==null||f.isBlank()||f.length()>160))return SchemaField.CONFLICTS;
        if(x.passengers()!=null&&(x.passengers()<2||x.passengers()>9))return SchemaField.PASSENGERS;
        if(x.budgetVndTotal()!=null&&(x.budgetVndTotal()<0||x.budgetVndTotal()>9007199254740991L))return SchemaField.BUDGET_VND_TOTAL;
        if(x.transmission()!=null&&!Set.of("AUTO","MANUAL").contains(x.transmission()))return SchemaField.TRANSMISSION;
        if(invalidName(x.pickupBranchName()))return SchemaField.PICKUP_BRANCH_NAME;
        if(invalidName(x.returnBranchName()))return SchemaField.RETURN_BRANCH_NAME;
        if(invalidDate(x.pickupDate()))return SchemaField.PICKUP_DATE;
        if(invalidDate(x.returnDate()))return SchemaField.RETURN_DATE;
        if(x.pickupDate()!=null&&x.returnDate()!=null
                &&!LocalDate.parse(x.returnDate()).isAfter(LocalDate.parse(x.pickupDate())))return SchemaField.DATE_ORDER;
        return SchemaField.NONE;
    }
    private static boolean invalidName(String name){
        return name!=null&&(name.isBlank()||name.length()>120);
    }
    private static boolean invalidDate(String date){
        if(date==null)return false;
        if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))return true;
        try{LocalDate.parse(date);return false;}
        catch(DateTimeParseException invalid){return true;}
    }
    private static DomainException unavailable(){return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"Kết quả đọc nhu cầu không hợp lệ; hãy dùng biểu mẫu.");}
}
