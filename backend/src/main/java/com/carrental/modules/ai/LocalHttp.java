package com.carrental.modules.ai;

import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Bounds both headers and body reads. Cancellation closes the response stream. */
public final class LocalHttp {
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(1);

    private LocalHttp() {}
    public record Response(int status,String body) {}
    public static final class ResponseTooLargeException extends java.io.IOException {
        public ResponseTooLargeException() {
            super("Provider response exceeded its byte limit.");
        }
    }
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
        Duration effectiveTimeout = timeout;
        boolean requestDeadlineLimits = false;
        if (boundedByDeadline) {
            Duration remaining = deadline.remaining();
            requestDeadlineLimits = remaining.compareTo(timeout) <= 0;
            if (requestDeadlineLimits) {
                effectiveTimeout = remaining;
            }
        }

        var executor=Executors.newVirtualThreadPerTaskExecutor();
        var stream=new java.util.concurrent.atomic.AtomicReference<java.io.InputStream>();
        Future<Response> future=executor.submit(()->{
            if (boundedByDeadline) {
                deadline.requireRemaining();
            }
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            var responseBody = response.body();
            try(var input=responseBody){
                stream.set(input);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                byte[] bytes=input.readNBytes(maxBytes+1);
                if(bytes.length>maxBytes)throw new ResponseTooLargeException();
                return new Response(response.statusCode(),new String(bytes,StandardCharsets.UTF_8));
            } finally {
                stream.compareAndSet(responseBody, null);
            }
        });
        Throwable primaryFailure = null;
        try {
            Response response;
            try {
                response=future.get(effectiveTimeout.toNanos(),TimeUnit.NANOSECONDS);
            } catch(TimeoutException error) {
                if (requestDeadlineLimits) {
                    throw new AiRequestDeadline.DeadlineExceededException();
                }
                throw error;
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (requestDeadlineLimits && cause instanceof HttpTimeoutException
                        && !(cause instanceof HttpConnectTimeoutException)) {
                    throw new AiRequestDeadline.DeadlineExceededException(cause);
                }
                if (cause instanceof Exception exception) throw exception;
                throw error;
            }
            if(boundedByDeadline)deadline.requireRemaining();
            return response;
        } catch (Exception | Error error) {
            primaryFailure = error;
            throw error;
        }
        finally {
            future.cancel(true);
            Exception cleanupFailure = null;
            var input=stream.getAndSet(null);
            if(input!=null)try{input.close();}catch(java.io.IOException error){cleanupFailure=error;}
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(CLEANUP_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                    Exception timeoutFailure =
                        new java.io.IOException("Provider HTTP task did not stop within the cleanup timeout.");
                    if (cleanupFailure == null) cleanupFailure = timeoutFailure;
                    else cleanupFailure.addSuppressed(timeoutFailure);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (cleanupFailure == null) cleanupFailure = error;
                else cleanupFailure.addSuppressed(error);
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }
}
