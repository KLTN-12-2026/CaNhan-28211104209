package com.carrental.modules.ai.parser;

import com.carrental.modules.booking.service.RentalBranchCatalog;
import com.carrental.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** One real Qwen request per case, through the normal extraction service on a synthetic catalog. */
class BranchRolesLiveIT {
    private static final String START="01/03/2027", END="03/03/2027";
    private static final List<RentalBranchCatalog.Item> ACTIVE=List.of(
        new RentalBranchCatalog.Item("sea","KDrive Biển"),new RentalBranchCatalog.Item("port","KDrive Cảng"),
        new RentalBranchCatalog.Item("north","KDrive Trung Tâm"),new RentalBranchCatalog.Item("south","KDrive Trung Tâm"));
    private void check(String request,String pickup,String returns,Integer people,Set<String> missing){
        assertTrue(Boolean.getBoolean("ai.eval.liveBranchRoles"),"Explicit live fixture opt-in required");
        var beans=new StaticListableBeanFactory();
        beans.addBean("qwen",new QwenCriteriaParser(new ObjectMapper(),"qwen3:8b"));
        var catalog=mock(RentalBranchCatalog.class);
        when(catalog.active()).thenReturn(ACTIVE);
        var service=new CriteriaExtractionService(beans.getBeanProvider(QwenCriteriaParser.class),catalog);
        var result=service.extract(new Actor("fixture","USER","Synthetic renter"),request);
        assertEquals(missing.isEmpty()?"READY_FOR_REVIEW":"NEEDS_CLARIFICATION",result.status());
        assertNotNull(result.draft());
        assertEquals(pickup,result.draft().pickupBranchId());
        assertEquals(returns,result.draft().returnBranchId());
        assertEquals(people,result.draft().passengers());
        assertEquals(missing,Set.copyOf(result.unresolvedFields()));
    }
    @Test void reversedRouteUsesTheReviewedRoles(){
        check("Nhận KDrive Cảng "+START+", trả KDrive Biển "+END+"; 4 người, tổng 3 triệu.",
            "port","sea",4,Set.of());
    }
    @Test void unselectedPickupRetainsKnownReturn(){
        check("Ngày nhận "+START+", trả "+END+" tại KDrive Biển; 4 người, tổng 3 triệu. Chưa chọn điểm nhận.",
            null,"sea",4,Set.of("pickupBranchName"));
    }
    @Test void inactiveDestinationCannotReusePickup(){
        check("Nhận KDrive Biển "+START+", trả KDrive Điểm Cũ "+END+"; 4 người, tổng 3 triệu.",
            "sea",null,4,Set.of("returnBranchName"));
    }
    @Test void duplicateActiveNameMustBeClarified(){
        check("Nhận KDrive Trung Tâm "+START+", trả KDrive Cảng "+END+"; 4 người, tổng 3 triệu.",
            null,"port",4,Set.of("pickupBranchName"));
    }
    @Test void branchAndPassengerAlternativesLeaveExactlyTwoQuestions(){
        check("Nhận "+START+" ở KDrive Biển hoặc KDrive Cảng, chưa chọn; trả KDrive Cảng "+END+
            ". Đi 3 hoặc 5 người, tổng 3 triệu.",null,"port",null,Set.of("pickupBranchName","passengers"));
    }
}
