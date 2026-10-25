package com.carrental;
import com.carrental.exception.DomainException;
import com.carrental.modules.location.entity.*;
import com.carrental.modules.location.repository.*;
import com.carrental.modules.location.service.LocationService;
import com.carrental.security.Actor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerLocationCatalogTest {
    @Test void userCatalogExcludesInactiveAndExposesOnlyCatalogFacts(){
        var repository=mock(LocationRepository.class);
        var category=new LocationCategory();category.name="Tham quan";
        var active=new Location();active.category=category;active.name="Địa điểm thử";active.status="ACTIVE";active.latitude=BigDecimal.ZERO;active.longitude=BigDecimal.ZERO;
        var hidden=new Location();hidden.status="INACTIVE";
        when(repository.findAllWithCategoryOrderByNameAsc()).thenReturn(List.of(active,hidden));
        var service=new LocationService(repository,mock(LocationCategoryRepository.class),new com.fasterxml.jackson.databind.ObjectMapper(),java.time.Clock.systemUTC());
        var result=service.activeCatalog(new Actor("user","USER","Khách"));
        assertEquals(1,result.size());assertEquals(active.id,result.get(0).id());assertEquals(BigDecimal.ZERO,result.get(0).latitude());
        for(String role:List.of("STAFF","ADMIN"))assertEquals(403,assertThrows(DomainException.class,()->service.activeCatalog(new Actor("other",role,"Other"))).status.value());
        verify(repository,times(1)).findAllWithCategoryOrderByNameAsc();
    }
}
