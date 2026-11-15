package com.carrental.modules.ai.parser;
import com.carrental.exception.DomainException;
import com.carrental.modules.ai.AiDiagnostics;
import com.carrental.modules.ai.AiRequestDeadline;
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
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList())).thenReturn(extraction("2026-10-20","2026-10-24","Đà Nẵng","KDrive Hà Nội"));
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("dn","KDrive Đà Nẵng"),new RentalBranchCatalog.Item("hn","KDrive Hà Nội")));
        var result=service(true).extract(user,"Từ20/10/2026 đến24/10/2026,4người,3triệu, nhận tại Đà Nẵng, trả tại Hà Nội");
        assertEquals("READY_FOR_REVIEW",result.status());assertEquals("dn",result.draft().pickupBranchId());assertEquals("hn",result.draft().returnBranchId());assertEquals(3000000,result.draft().budgetVndTotal());
    }
    @Test void ambiguousInactiveAndInventedYearCannotResolve(){
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList())).thenReturn(extraction("2026-10-20","2026-10-24","Đà Nẵng","Huế"));
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("a","KDrive Đà Nẵng"),new RentalBranchCatalog.Item("b","Đà Nẵng")));
        var result=service(true).extract(user,"20/10 đến24/10");assertEquals("NEEDS_CLARIFICATION",result.status());assertNull(result.draft().pickupDate());assertNull(result.draft().pickupBranchId());assertNull(result.draft().returnBranchId());
    }
    @Test void keepOnlyTheUnknownRoleAndUndecidedPartySizeUnresolved(){
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("b","KDrive Biển")));
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(new CriteriaParser.Extraction("NEEDS_CLARIFICATION","2027-03-01","2027-03-03",
                "KDrive Biển","KDrive Biển",3,3_000_000L,null,List.of("pickupBranchName"),List.of()));
        var result=service(true).extract(user,"Nhận KDrive Biển 01/03/2027, trả KDrive Núi 03/03/2027; đi 3 hoặc 5 người, tổng 3 triệu.");
        assertEquals("NEEDS_CLARIFICATION",result.status());
        assertEquals("b",result.draft().pickupBranchId());
        assertNull(result.draft().returnBranchId());
        assertNull(result.draft().passengers());
        assertEquals(java.util.Set.of("returnBranchName","passengers"),java.util.Set.copyOf(result.unresolvedFields()));
    }
    @Test void explicitSharedBranchClearsStaleUncertaintyButDuplicateNamesCannotResolve(){
        var unique=new RentalBranchCatalog.Item("b","KDrive Biển");
        when(branches.active()).thenReturn(List.of(unique),List.of(unique,new RentalBranchCatalog.Item("b2","Biển")));
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(new CriteriaParser.Extraction("NEEDS_CLARIFICATION","2027-03-01","2027-03-03",
                null,null,4,3_000_000L,null,List.of("pickupBranchName","returnBranchName"),List.of()));
        var service=service(true);
        String text="KDrive Biển hai đầu từ 01/03/2027 tới 03/03/2027, 4 người, tổng 3 triệu.";
        var resolved=service.extract(user,text);
        assertEquals("READY_FOR_REVIEW",resolved.status());
        assertEquals("b",resolved.draft().pickupBranchId());
        assertEquals("b",resolved.draft().returnBranchId());
        var duplicate=service.extract(user,text);
        assertEquals("NEEDS_CLARIFICATION",duplicate.status());
        assertNull(duplicate.draft().pickupBranchId());
        assertNull(duplicate.draft().returnBranchId());
    }
    @Test void explicitUncertaintyWinsOverGuessesEvenWhenAlternativesContainAComma(){
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("b","KDrive Biển"),new RentalBranchCatalog.Item("c","KDrive Cảng")));
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(extraction("2027-03-01","2027-03-03","KDrive Biển","KDrive Cảng"));
        var service=service(true);
        String trip=" Trả tại KDrive Cảng 03/03/2027; ngày nhận 01/03/2027; tổng 3 triệu.";
        var undecided=service.extract(user,"Nhận KDrive Biển, hoặc KDrive Cảng, chưa chọn; ba hoặc năm người."+trip);
        assertEquals("NEEDS_CLARIFICATION",undecided.status());
        assertNull(undecided.draft().pickupBranchId());
        assertEquals("c",undecided.draft().returnBranchId());
        assertNull(undecided.draft().passengers());
        assertEquals(java.util.Set.of("pickupBranchName","passengers"),java.util.Set.copyOf(undecided.unresolvedFields()));
        var unset=service.extract(user,"Điểm nhận chưa chọn. 4 người."+trip);
        assertEquals(List.of("pickupBranchName"),unset.unresolvedFields());
        assertNull(unset.draft().pickupBranchId());
        assertEquals("c",unset.draft().returnBranchId());
    }
    @Test void trailingSharedNameConfirmsRolesButCannotInventYearsOrFillAMissingRole(){
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("b","KDrive Biển")));
        var complete=new CriteriaParser.Extraction("NEEDS_CLARIFICATION",null,null,"KDrive Biển","KDrive Biển",
            4,3_000_000L,null,List.of("returnBranchName"),List.of());
        var absent=new CriteriaParser.Extraction("NEEDS_CLARIFICATION",null,null,null,"KDrive Biển",
            4,3_000_000L,null,List.of("pickupBranchName"),List.of());
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList())).thenReturn(complete,absent);
        String text="Nhận 04/03, trả 07/03, KDrive Biển; 4 người, tổng 3 triệu.";
        var service=service(true);
        var known=service.extract(user,text);
        assertEquals("b",known.draft().pickupBranchId());
        assertEquals("b",known.draft().returnBranchId());
        assertEquals(java.util.Set.of("pickupDate","returnDate"),java.util.Set.copyOf(known.unresolvedFields()));
        var unspecified=service.extract(user,text);
        assertNull(unspecified.draft().pickupBranchId());
        assertTrue(unspecified.unresolvedFields().contains("pickupBranchName"));
    }
    @Test void branchNamesMustBeMentionedAndUniqueBeforeBinding(){
        var active=List.of(new RentalBranchCatalog.Item("dn","Đà Nẵng · mẫu"),
            new RentalBranchCatalog.Item("hn","Hà Nội · mẫu"), new RentalBranchCatalog.Item("h","Huế"));
        when(branches.active()).thenReturn(active);
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(extraction(null,null,"Hà Nội · mẫu","Đà Nẵng · mẫu"))
            .thenReturn(extraction(null,null,"Huế","Hà Nội · mẫu"));
        var service=service(true);
        String request="Nhận tại Hà Nội · mẫu, trả tại Đà Nẵng · mẫu";
        var draft=service.extract(user,request).draft();
        assertEquals("hn",draft.pickupBranchId());
        assertEquals("dn",draft.returnBranchId());
        verify(parser).parse(eq(request),any(AiRequestDeadline.class),eq(List.of("Hà Nội · mẫu","Đà Nẵng · mẫu")));
        var absent=service.extract(user,"Xe HuếX nhận tại một nơi chưa biết").draft();
        assertNull(absent.pickupBranchId());
        assertNull(absent.returnBranchId());
    }
    @Test void permissionsDisabledAndSensitiveIdentifiersNeverInvokeModel(){
        assertEquals(403,assertThrows(DomainException.class,()->service(true).extract(new Actor("s","STAFF","Staff"),"xe")).status.value());
        var diagnostics=new AiDiagnostics();
        var disabledService=new CriteriaExtractionService(
            new StaticListableBeanFactory().getBeanProvider(QwenCriteriaParser.class),branches,diagnostics,"qwen3:8b");
        assertEquals(503,assertThrows(DomainException.class,()->disabledService.extract(user,"xe")).status.value());
        assertEquals(AiDiagnostics.Cause.DISABLED,diagnostics.snapshot().get(0).cause());
        for(String text:List.of("email test@example.test","gọi0901234567","GPLX1234567890123"))assertEquals(400,assertThrows(DomainException.class,()->service(true).extract(user,text)).status.value());
        verifyNoInteractions(parser,branches);
    }
    @Test void conflictsAndUnrelatedIntentCannotBecomeDraft(){
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList())).thenReturn(new CriteriaParser.Extraction("OUT_OF_SCOPE",null,null,null,null,null,null,null,List.of(),List.of()));
        assertNull(service(true).extract(user,"viết thơ").draft());
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList())).thenReturn(new CriteriaParser.Extraction("NEEDS_CLARIFICATION",null,null,null,null,null,null,null,List.of(),List.of("dates")));
        assertNull(service(true).extract(user,"ngày mâu thuẫn").draft());verify(branches,times(2)).active();verifyNoMoreInteractions(branches);
    }
    @Test void modelCannotInventDatesOrOptionalTransmissionFromUnstatedInput(){
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList())).thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE","2026-10-20","2026-10-24","Đà Nẵng","Đà Nẵng",4,3000000L,"AUTO",List.of("transmission"),List.of()));
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("dn","KDrive Đà Nẵng")));
        var complete=service(true).extract(user,"20/10/2026 đến24/10/2026,4người,3triệu,nhậntrảĐàNẵng");
        assertEquals("READY_FOR_REVIEW",complete.status());assertNull(complete.draft().transmission());
        var invented=service(true).extract(user,"Chưa chọn ngày, ngân sách2026 đồng");assertNull(invented.draft().pickupDate());
        assertEquals("AUTO",service(true).extract(user,"20/10/2026 đến24/10/2026 xe tự động").draft().transmission());
    }
    @Test void canonicalAutoIsRecognizedOnlyAsAnExplicitPositiveTerm(){
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(extraction(null,null,null,null));
        var service=service(true);
        assertEquals("AUTO",service.extract(user,"Muốn hộp số AUTO").draft().transmission());
        var negative=service.extract(user,"Không muốn hộp số AUTO");
        assertNull(negative.draft().transmission());
        assertTrue(negative.unresolvedFields().contains("transmission"));
        for(String text:List.of("Muốn xe 5 chỗ","Có Android AutoPlay không?")){
            var optional=service.extract(user,text);
            assertNull(optional.draft().transmission());
            assertFalse(optional.unresolvedFields().contains("transmission"));
        }
    }
    @Test void bindsOnlyAStatedTripTotalAndExplicitTransmission(){
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,null,null,4,4_000_000L,
                "AUTO",List.of("budgetVndTotal"),List.of()));
        var service=service(true);
        var positive=service.extract(user,"Tôi dành bốn triệu rưỡi đồng cho cả chuyến, chọn số tay.");
        assertEquals(4_500_000L,positive.draft().budgetVndTotal());
        assertFalse(positive.unresolvedFields().contains("budgetVndTotal"));
        assertEquals("MANUAL",positive.draft().transmission());
        var mixed=service.extract(user,"500.000 đồng mỗi ngày; ngân sách tổng 3.200.000 đồng.");
        assertEquals(3_200_000L,mixed.draft().budgetVndTotal());
        assertFalse(mixed.unresolvedFields().contains("budgetVndTotal"));
        for(String text:List.of("500.000 đồng mỗi ngày","500k/ngày","Ngân sách 2 triệu hoặc 4 triệu, chưa chọn.","Tổng 2 triệu, hoặc 4 triệu.",
                "Tổng tiền 2 hoặc 4 triệu.","Ngân sách tôi chưa chốt.")){
            var ambiguous=service.extract(user,text);
            assertNull(ambiguous.draft().budgetVndTotal());
            assertTrue(ambiguous.unresolvedFields().contains("budgetVndTotal"));
        }
        assertNull(service.extract(user,"Xe có Android Auto").draft().transmission());
        for(String text:List.of("Đừng chọn số sàn.","Không muốn hộp số: AUTO")){
            var refused=service.extract(user,text);
            assertNull(refused.draft().transmission());
            assertTrue(refused.unresolvedFields().contains("transmission"));
        }
        assertEquals("MANUAL",service.extract(user,"Dùng xe số sàn.").draft().transmission());
        var flexible=service.extract(user,"Số sàn hoặc tự động đều được.");
        assertNull(flexible.draft().transmission());
        assertFalse(flexible.unresolvedFields().contains("transmission"));
    }
    @Test void unrelatedNegationDoesNotEraseTransmissionAndDirectNegationIsNotInverted(){
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("dn","KDrive Đà Nẵng")));
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,"Đà Nẵng","Đà Nẵng",4,3000000L,"AUTO",List.of(),List.of()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,"Đà Nẵng","Đà Nẵng",4,3000000L,"MANUAL",List.of(),List.of()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,"Đà Nẵng","Đà Nẵng",4,3000000L,"AUTO",List.of(),List.of()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,"Đà Nẵng","Đà Nẵng",4,3000000L,"AUTO",List.of(),List.of()));

        var unrelated=service(true).extract(user,"Không cần xe màu đỏ nhưng vẫn muốn xe số tự động.");
        assertEquals("AUTO",unrelated.draft().transmission());
        var directlyNegated=service(true).extract(user,"Không muốn xe số sàn.");
        assertNull(directlyNegated.draft().transmission());
        assertTrue(directlyNegated.unresolvedFields().contains("transmission"));
        var explicitAlternative=service(true).extract(user,"Không muốn xe số sàn, ưu tiên xe tự động.");
        assertEquals("AUTO",explicitAlternative.draft().transmission());
        var englishNegation=service(true).extract(user,"not automatic");
        assertNull(englishNegation.draft().transmission());
        assertTrue(englishNegation.unresolvedFields().contains("transmission"));
    }
    @Test void ambiguousTransmissionCannotProduceReadyDraft(){
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("dn","KDrive Đà Nẵng")));
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(extraction("2026-10-20","2026-10-24","Đà Nẵng","Đà Nẵng"))
            .thenReturn(extraction("2026-10-20","2026-10-24","Đà Nẵng","Đà Nẵng"));
        var negative=service(true).extract(user,"Từ20/10/2026 đến24/10/2026,4người,3triệu, nhậntrảĐàNẵng, không muốn xe số sàn.");
        assertEquals("NEEDS_CLARIFICATION",negative.status());
        assertEquals(List.of("transmission"),negative.unresolvedFields());
        assertNull(negative.draft().transmission());

        var conflicting=service(true).extract(user,"Từ20/10/2026 đến24/10/2026,4người,3triệu, nhậntrảĐàNẵng, xe số sàn và xe số tự động.");
        assertEquals("NEEDS_CLARIFICATION",conflicting.status());
        assertEquals(List.of("transmission"),conflicting.unresolvedFields());
        assertNull(conflicting.draft().transmission());
    }
    @Test void longTransmissionNegationCannotSelectAnOppositeGearbox(){
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("dn","KDrive Đà Nẵng")));
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,"Đà Nẵng","Đà Nẵng",4,3000000L,"AUTO",List.of(),List.of()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,"Đà Nẵng","Đà Nẵng",4,3000000L,"MANUAL",List.of(),List.of()));

        var automatic=service(true).extract(user,"Tôi không muốn thuê loại xe có hộp số tự động");
        var manual=service(true).extract(user,"Tôi không muốn thuê loại xe có hộp số sàn");
        assertAll(
            () -> {assertNull(automatic.draft().transmission());assertTrue(automatic.unresolvedFields().contains("transmission"));},
            () -> {assertNull(manual.draft().transmission());assertTrue(manual.unresolvedFields().contains("transmission"));}
        );
    }
    @Test void unrelatedNegationDoesNotEraseAutomaticChoiceAcrossConjunction(){
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("dn","KDrive Đà Nẵng")));
        when(parser.parse(anyString(),any(AiRequestDeadline.class),anyList()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,"Đà Nẵng","Đà Nẵng",4,3000000L,"AUTO",List.of(),List.of()))
            .thenReturn(new CriteriaParser.Extraction("RECOMMEND_VEHICLE",null,null,"Đà Nẵng","Đà Nẵng",4,3000000L,"AUTO",List.of(),List.of()));

        var vietnamese=service(true).extract(user,"Tôi không hút thuốc và muốn xe tự động");
        var english=service(true).extract(user,"I do not smoke and want an automatic car");
        assertAll(
            () -> {assertEquals("AUTO",vietnamese.draft().transmission());assertFalse(vietnamese.unresolvedFields().contains("transmission"));},
            () -> {assertEquals("AUTO",english.draft().transmission());assertFalse(english.unresolvedFields().contains("transmission"));}
        );
    }
}
