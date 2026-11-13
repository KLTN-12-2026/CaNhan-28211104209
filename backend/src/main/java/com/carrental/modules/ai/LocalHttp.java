package com.carrental.modules.ai;

import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Bounds both headers and body reads. Cancellation closes the response stream. */
public final class LocalHttp {
    private LocalHttp() {}
    public record Response(int status,String body) {}
    public static Response send(HttpClient client,HttpRequest request,Duration timeout,int maxBytes) throws Exception {
        return sendInternal(client, request, timeout, maxBytes, null, false);
    }
    public static Response send(
            HttpClient client,
            HttpRequest request,
            Duration providerMaximum,
            int maxBytes,
            AiRequestDeadline deadline) throws Exception {
        return sendInternal(client, request, providerMaximum, maxBytes, deadline, true);
    }
    private static Response sendInternal(
            HttpClient client,
            HttpRequest request,
            Duration timeout,
            int maxBytes,
            AiRequestDeadline deadline,
            boolean boundedByDeadline) throws Exception {
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        var stream=new java.util.concurrent.atomic.AtomicReference<java.io.InputStream>();
        Future<Response> future=executor.submit(()->{
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()){
                stream.set(input);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                byte[] bytes=input.readNBytes(maxBytes+1);
                if(bytes.length>maxBytes)throw new java.io.IOException("Response exceeds limit");
                return new Response(response.statusCode(),new String(bytes,StandardCharsets.UTF_8));
            }
        });
        try {
            Duration effectiveTimeout=boundedByDeadline?deadline.timeoutFor(timeout):timeout;
            Response response;
            try {
                response=future.get(effectiveTimeout.toNanos(),TimeUnit.NANOSECONDS);
            } catch(TimeoutException error) {
                if(boundedByDeadline)throw new AiRequestDeadline.DeadlineExceededException();
                throw error;
            }
            if(boundedByDeadline)deadline.requireRemaining();
            return response;
        }
        finally {
            future.cancel(true);
            var input=stream.get();if(input!=null)try{input.close();}catch(java.io.IOException ignored){}
            executor.shutdownNow();
        }
    }
}
