package ai.streamsphere.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client token bucket (bounded LRU of 10k clients). Single-replica, in-memory by design for the
 * one-node free profile; a multi-replica deployment would move this to Valkey.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RateLimitFilter extends OncePerRequestFilter {
    static final class Bucket {
        double tokens;
        long last;
        Bucket(double t, long now) { tokens = t; last = now; }
    }

    private final Map<String, Bucket> buckets = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bucket> e) { return size() > 10_000; }
    });
    private final double perSecond;
    private final double burst;
    private final double writePerSecond;
    private final MeterRegistry meters;

    public RateLimitFilter(@Value("${streamsphere.rate-limit.per-second:30}") double perSecond,
                           @Value("${streamsphere.rate-limit.burst:120}") double burst,
                           @Value("${streamsphere.rate-limit.write-per-second:5}") double writePerSecond, MeterRegistry meters) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.writePerSecond = writePerSecond;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/") || req.getRequestURI().startsWith("/api/v1/playback/stream/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        boolean write = !req.getMethod().equals("GET") && !req.getMethod().equals("OPTIONS");
        String client = req.getRemoteAddr() + (write ? "|w" : "|r");
        double rate = write ? writePerSecond : perSecond;
        double cap = write ? writePerSecond * 6 : burst;
        long now = System.nanoTime();
        boolean allowed;
        synchronized (buckets) {
            Bucket b = buckets.computeIfAbsent(client, k -> new Bucket(cap, now));
            b.tokens = Math.min(cap, b.tokens + (now - b.last) / 1e9 * rate);
            b.last = now;
            allowed = b.tokens >= 1;
            if (allowed) b.tokens -= 1;
        }
        if (!allowed) {
            meters.counter("streamsphere_gateway_rate_limited_total", "kind", write ? "write" : "read").increment();
            res.setStatus(429);
            res.setHeader("Retry-After", "1");
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"type\":\"https://streamsphere.dev/problems/rate-limited\",\"title\":\"Too Many Requests\",\"status\":429}");
            return;
        }
        chain.doFilter(req, res);
    }
}
