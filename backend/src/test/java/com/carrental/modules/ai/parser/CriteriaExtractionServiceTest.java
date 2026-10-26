package com.carrental.modules.ai.parser;
import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.RentalBranchCatalog;
import com.carrental.security.Actor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CriteriaExtractionServiceTest {
    final QwenCriteriaParser parser=mock(QwenCriteriaParser.class);
    final RentalBranchCatalog branches=mock(RentalBranchCatalog.class);
    final Actor user=new Actor("u","USER","Customer");
    CriteriaExtractionService service(boolean enabled){var beans=new StaticListableBeanFactory();if(enabled)beans.addBean("qwen",parser);return new CriteriaExtractionService(beans.getBeanProvider(QwenCriteriaParser.class),branches);}
    CriteriaParser.Extraction extraction(String start,String end,String pickup,String destination){return new CriteriaParser.Extraction("RECOMMEND_VEHICLE",start,end,pickup,destination,4,3000000L,"AUTO",List.of(),List.of());}
    @Test void resolvesActiveNamesOnlyAndReturnsReviewableDraft(){
        when(parser.parse(anyString())).thenReturn(extraction("2026-10-20","2026-10-24","Đà Nẵng","KDrive Hà Nội"));
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("dn","KDrive Đà Nẵng"),new RentalBranchCatalog.Item("hn","KDrive Hà Nội")));
        var result=service(true).extract(user,"Từ20/10/2026 đến24/10/2026,4người,3triệu");
        assertEquals("READY_FOR_REVIEW",result.status());assertEquals("dn",result.draft().pickupBranchId());assertEquals("hn",result.draft().returnBranchId());assertEquals(3000000,result.draft().budgetVndTotal());
    }
    @Test void ambiguousInactiveAndInventedYearCannotResolve(){
        when(parser.parse(anyString())).thenReturn(extraction("2026-10-20","2026-10-24","Đà Nẵng","Huế"));
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("a","KDrive Đà Nẵng"),new RentalBranchCatalog.Item("b","Đà Nẵng")));
        var result=service(true).extract(user,"20/10 đến24/10");assertEquals("NEEDS_CLARIFICATION",result.status());assertNull(result.draft().pickupDate());assertNull(result.draft().pickupBranchId());assertNull(result.draft().returnBranchId());
    }
    @Test void permissionsDisabledAndSensitiveIdentifiersNeverInvokeModel(){
        assertEquals(403,assertThrows(DomainException.class,()->service(true).extract(new Actor("s","STAFF","Staff"),"xe")).status.value());
        assertEquals(503,assertThrows(DomainException.class,()->service(false).extract(user,"xe")).status.value());
        for(String text:List.of("email test@example.test","gọi0901234567","GPLX1234567890123"))assertEquals(400,assertThrows(DomainException.class,()->service(true).extract(user,text)).status.value());
        verifyNoInteractions(parser,branches);
    }
    @Test void conflictsAndUnrelatedIntentCannotBecomeDraft(){
        when(parser.parse(anyString())).thenReturn(new CriteriaParser.Extraction("OUT_OF_SCOPE",null,null,null,null,null,null,null,List.of(),List.of()));
        assertNull(service(true).extract(user,"viết thơ").draft());
        when(parser.parse(anyString())).thenReturn(new CriteriaParser.Extraction("NEEDS_CLARIFICATION",null,null,null,null,null,null,null,List.of(),List.of("dates")));
        assertNull(service(true).extract(user,"ngày mâu thuẫn").draft());verifyNoInteractions(branches);
    }
    @Test void modelCannotInventDatesOrOptionalTransmissionFromUnstatedInput(){
        when(parser.parse(anyString())).thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE","2026-10-20","2026-10-24","Đà Nẵng","Đà Nẵng",4,3000000L,"AUTO",List.of("transmission"),List.of()));
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("dn","KDrive Đà Nẵng")));
        var complete=service(true).extract(user,"20/10/2026 đến24/10/2026,4người,3triệu,nhậntrảĐàNẵng");
        assertEquals("READY_FOR_REVIEW",complete.status());assertNull(complete.draft().transmission());
        var invented=service(true).extract(user,"Chưa chọn ngày, ngân sách2026 đồng");assertNull(invented.draft().pickupDate());
        assertEquals("AUTO",service(true).extract(user,"20/10/2026 đến24/10/2026 xe tự động").draft().transmission());
    }
}
