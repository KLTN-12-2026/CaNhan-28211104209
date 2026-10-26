package com.carrental.modules.ai;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalHttpTest {
    private HttpServer server;
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    private HttpRequest request(boolean stall)throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write(new byte[32]);exchange.getResponseBody().flush();
            if(stall)Thread.sleep(1500);
        }catch(Exception ignored){}finally{exchange.close();}});server.start();
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).build();
    }
    @Test void responseBodyCannotExceedByteLimit()throws Exception{
        var request=request(false);
        assertThrows(java.util.concurrent.ExecutionException.class,()->LocalHttp.send(HttpClient.newHttpClient(),request,Duration.ofSeconds(2),16));
    }
    @Test void deadlineAlsoBoundsStalledBodyAfterHeaders()throws Exception{
        var request=request(true);long start=System.nanoTime();
        assertThrows(TimeoutException.class,()->LocalHttp.send(HttpClient.newHttpClient(),request,Duration.ofMillis(150),1024));
        assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(1))<0);
    }
}
