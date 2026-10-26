package com.carrental.modules.ai.routing;
import com.carrental.exception.DomainException;
import com.carrental.modules.location.service.LocationService;
import com.carrental.security.Actor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocationRouteServiceTest {
    private final Actor user=new Actor("u","USER","Khách");
    private final LocationService locations=mock(LocationService.class);
    private final OsrmRoutingClient routing=mock(OsrmRoutingClient.class);
    @Test void resolvesCoordinatesFromCatalogAndDoesNotWrite(){
        when(locations.activeCatalog(user)).thenReturn(List.of(
            new LocationService.PublicView("a","c","Loại","A",new BigDecimal("16"),new BigDecimal("108")),
            new LocationService.PublicView("b","c","Loại","B",new BigDecimal("15"),new BigDecimal("109"))));
        var expected=new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.NO_ROUTE,null,null,"OSM");
        when(routing.route(List.of(new OsrmRoutingClient.Point(16,108),new OsrmRoutingClient.Point(15,109)))).thenReturn(expected);
        assertEquals(expected,new LocationRouteService(locations,routing,true).preview(user,List.of("a","b")));
        verify(locations).activeCatalog(user);verifyNoMoreInteractions(locations);
    }
    @Test void invalidInactiveMissingAndDuplicateIdsNeverReachRouter(){
        when(locations.activeCatalog(user)).thenReturn(List.of());var service=new LocationRouteService(locations,routing,true);
        for(var ids:List.of(List.of("a","a"),List.of("a","missing"),List.of("a")))
            assertEquals(400,assertThrows(DomainException.class,()->service.preview(user,ids)).status.value());
        verifyNoInteractions(routing);
    }
    @Test void disabledAndNonUserRequestsDoNotReadCatalogOrRoute(){
        assertEquals(503,assertThrows(DomainException.class,()->new LocationRouteService(locations,routing,false).preview(user,List.of("a","b"))).status.value());
        for(String role:List.of("STAFF","ADMIN"))assertEquals(403,assertThrows(DomainException.class,()->new LocationRouteService(locations,routing,true).preview(new Actor("x",role,"Other"),List.of("a","b"))).status.value());
        verifyNoInteractions(locations,routing);
    }
    @Test void localRouterHasBoundedConcurrencyAndReleasesCapacity() throws Exception {
        when(locations.activeCatalog(user)).thenReturn(List.of(
            new LocationService.PublicView("a","c","Loại","A",BigDecimal.ZERO,BigDecimal.ZERO),
            new LocationService.PublicView("b","c","Loại","B",BigDecimal.ONE,BigDecimal.ONE)));
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        when(routing.route(anyList())).thenAnswer(call->{entered.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));return new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.NO_ROUTE,null,null,"OSM");});
        var service=new LocationRouteService(locations,routing,true);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->service.preview(user,List.of("a","b")));
            var second=executor.submit(()->service.preview(user,List.of("a","b")));
            try{assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(429,assertThrows(DomainException.class,()->service.preview(user,List.of("a","b"))).status.value());}
            finally{release.countDown();}
            assertNotNull(first.get());assertNotNull(second.get());
            assertNotNull(service.preview(user,List.of("a","b")));
        }
    }
}
