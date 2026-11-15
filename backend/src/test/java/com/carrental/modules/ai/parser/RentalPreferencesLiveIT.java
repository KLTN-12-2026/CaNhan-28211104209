package com.carrental.modules.ai.parser;

import com.carrental.modules.booking.service.RentalBranchCatalog;
import com.carrental.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in Qwen regression for gearbox preferences and whole-trip budgets. */
class RentalPreferencesLiveIT {
    @Test void verifyPreferencesThroughExtractionService(){
        assertTrue(Boolean.getBoolean("ai.eval.livePreferences"),"Explicit live fixture opt-in required");
        var beans=new StaticListableBeanFactory();
        beans.addBean("qwen",new QwenCriteriaParser(new ObjectMapper(),"qwen3:8b"));
        var branches=mock(RentalBranchCatalog.class);
        when(branches.active()).thenReturn(List.of(new RentalBranchCatalog.Item("b","KDrive Biển")));
        var service=new CriteriaExtractionService(beans.getBeanProvider(QwenCriteriaParser.class),branches);
        var user=new Actor("fixture","USER","Synthetic fixture");
        String trip="Thuê cho 4 người, nhận và trả tại KDrive Biển từ 01/03/2027 tới 03/03/2027. ";
        assertAll(
            ()->{
                var draft=service.extract(user,trip+"Tôi dành bốn triệu rưỡi đồng cho cả chuyến, chọn xe số tay.");
                assertNotNull(draft.draft());
                assertEquals("MANUAL",draft.draft().transmission());
                assertEquals(4_500_000L,draft.draft().budgetVndTotal());
                assertFalse(draft.unresolvedFields().contains("transmission"));
                assertFalse(draft.unresolvedFields().contains("budgetVndTotal"));
            },
            ()->{
                var draft=service.extract(user,trip+"Tôi muốn hộp số AUTO. Ngân sách 500k/ngày.");
                assertNotNull(draft.draft());
                assertEquals("NEEDS_CLARIFICATION",draft.status());
                assertEquals("AUTO",draft.draft().transmission());
                assertNull(draft.draft().budgetVndTotal());
                assertTrue(draft.unresolvedFields().contains("budgetVndTotal"));
            },
            ()->{
                var draft=service.extract(user,trip+"Không muốn xe số sàn. Ngân sách 2 triệu hoặc 4 triệu, chưa chọn.");
                assertNotNull(draft.draft());
                assertNull(draft.draft().transmission());
                assertNull(draft.draft().budgetVndTotal());
                assertTrue(draft.unresolvedFields().containsAll(List.of("transmission","budgetVndTotal")));
            },
            ()->{
                var draft=service.extract(user,trip+"Số sàn hoặc tự động đều được. Tổng 3.000.000 đồng.");
                assertNotNull(draft.draft());
                assertNull(draft.draft().transmission());
                assertFalse(draft.unresolvedFields().contains("transmission"));
                assertEquals(3_000_000L,draft.draft().budgetVndTotal());
            });
    }
}
