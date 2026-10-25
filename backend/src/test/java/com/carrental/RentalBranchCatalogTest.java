package com.carrental;
import com.carrental.modules.booking.service.RentalBranchCatalog;
import com.carrental.modules.branch.service.BranchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RentalBranchCatalogTest {
    @Test void onlyActiveRentalPointsCrossTheModuleBoundary(){
        var branches=mock(BranchService.class);
        when(branches.list()).thenReturn(List.of(new BranchService.BranchView("a","A","address","ACTIVE",2),new BranchService.BranchView("x","X","private","INACTIVE",0)));
        assertEquals(List.of(new RentalBranchCatalog.Item("a","A")),new RentalBranchCatalog(branches).active());
        verify(branches).list();verifyNoMoreInteractions(branches);
    }
}
