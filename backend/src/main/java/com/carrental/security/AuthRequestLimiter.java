package com.carrental.security;

import com.carrental.exception.DomainException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Fixed-window protection for this single-process modular monolith. */
@Component
public class AuthRequestLimiter {
    private static final long WINDOW_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_KEYS = 10000;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private record Bucket(long started, int count) {}
    public AuthRequestLimiter(Clock clock) { this.clock = clock; }
    public synchronized void check(String operation, String subject, int limit) {
        long now = clock.millis(); String key = operation + ":" + subject;
        Bucket bucket = buckets.get(key);
        if (bucket == null || now - bucket.started() >= WINDOW_MILLIS) {
            if (bucket == null && buckets.size() >= MAX_KEYS) {
                buckets.entrySet().removeIf(e -> now - e.getValue().started() >= WINDOW_MILLIS);
                if (buckets.size() >= MAX_KEYS) reject();
            }
            bucket = new Bucket(now, 0);
        }
        if (bucket.count() >= limit) reject();
        buckets.put(key, new Bucket(bucket.started(), bucket.count() + 1));
    }
    private void reject() {
        throw new DomainException(HttpStatus.TOO_MANY_REQUESTS,
            "Quá nhiều yêu cầu. Vui lòng thử lại sau tối đa 15 phút.");
    }
}
