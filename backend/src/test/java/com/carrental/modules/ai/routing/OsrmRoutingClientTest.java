package com.carrental.modules.ai.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OsrmRoutingClientTest {
    private HttpServer server;
    private String path;

    @AfterEach void stop(){if(server!=null)server.stop(0);}

    private OsrmRoutingClient client(int status,String body)throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{
            path=x.getRequestURI().toString();
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status,bytes.length);
            x.getResponseBody().write(bytes);
            x.close();
        });
        server.start();
        return new OsrmRoutingClient(new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
    }

    private List<OsrmRoutingClient.Point> points(){
        return List.of(new OsrmRoutingClient.Point(16.05,108.2),new OsrmRoutingClient.Point(15.88,108.33));
    }

    @Test void returnsProviderFactsInMetersSecondsWithCorrectCoordinateOrder()throws Exception{
        var result=client(200,"{\"code\":\"Ok\",\"routes\":[{\"distance\":30000,\"duration\":2200,\"legs\":[{\"distance\":30000,\"duration\":2200}]}]}").route(points());
        assertEquals(OsrmRoutingClient.Status.VERIFIED,result.status());
        assertEquals(30000,result.distanceMeters());
        assertEquals(2200,result.durationSeconds());
        assertEquals(1, result.legs().size());
        assertEquals(30000, result.legs().get(0).distanceMeters());
        assertEquals(2200, result.legs().get(0).durationSeconds());
        assertTrue(path.contains("108.2,16.05;108.33,15.88"));
        assertTrue(path.contains("radiuses=1000;1000"));
        assertTrue(result.attribution().contains("OpenStreetMap"));
    }

    @Test void noRouteHasNoInventedEstimate()throws Exception{
        var result=client(200,"{\"code\":\"NoRoute\"}").route(points());
        assertEquals(OsrmRoutingClient.Status.NO_ROUTE,result.status());
        assertNull(result.durationSeconds());
        assertNull(result.distanceMeters());
    }

    @Test void malformedAndNegativeFactsFailClosed()throws Exception{
        var result=client(200,"{\"code\":\"Ok\",\"routes\":[{\"distance\":-1,\"duration\":2}]}").route(points());
        assertEquals(OsrmRoutingClient.Status.UNAVAILABLE,result.status());
        assertNull(result.distanceMeters());
    }

    @Test void negativeOrMalformedLegsFailClosed()throws Exception{
        // Negative leg distance
        var res1 = client(200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":1000,\"duration\":100,\"legs\":[{\"distance\":-10,\"duration\":100}]}]}").route(points());
        assertEquals(OsrmRoutingClient.Status.UNAVAILABLE, res1.status());

        // Negative leg duration
        var res2 = client(200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":1000,\"duration\":100,\"legs\":[{\"distance\":1000,\"duration\":-5}]}]}").route(points());
        assertEquals(OsrmRoutingClient.Status.UNAVAILABLE, res2.status());

        // Mismatched legs count (2 points must have 1 leg)
        var res3 = client(200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":1000,\"duration\":100,\"legs\":[]}]}").route(points());
        assertEquals(OsrmRoutingClient.Status.UNAVAILABLE, res3.status());
    }

    @Test void unavailableServiceDoesNotBecomeNoRoute()throws Exception{
        assertEquals(OsrmRoutingClient.Status.UNAVAILABLE,client(503,"internal detail").route(points()).status());
    }

    @Test void invalidCoordinatesAndRemoteHostsRejected(){
        assertThrows(IllegalArgumentException.class,()->new OsrmRoutingClient.Point(Double.NaN,1));
        assertThrows(IllegalArgumentException.class,()->new OsrmRoutingClient.Point(91,1));
        assertThrows(IllegalArgumentException.class,()->new OsrmRoutingClient(new ObjectMapper(),URI.create("http://example.com")));
        assertThrows(IllegalArgumentException.class,()->new OsrmRoutingClient(new ObjectMapper()).route(List.of()));
    }

    @Test void noSegment400IsNoRouteWithoutEstimate()throws Exception{
        var result=client(400,"{\"code\":\"NoSegment\"}").route(points());
        assertEquals(OsrmRoutingClient.Status.NO_ROUTE,result.status());
        assertNull(result.durationSeconds());
    }
}
