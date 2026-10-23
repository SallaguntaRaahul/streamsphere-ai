package ai.streamsphere.common.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cache-aside over Valkey that never fails the request: any cache error is counted and the caller
 * falls through to the source of truth.
 */
public class SafeCache {
    private static final Logger log = LoggerFactory.getLogger(SafeCache.class);
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public SafeCache(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    public Optional<String> get(String key) {
        try {
            String v = redis.opsForValue().get(key);
            meters.counter("streamsphere_cache_requests_total", "result", v == null ? "miss" : "hit").increment();
            return Optional.ofNullable(v);
        } catch (RuntimeException e) {
            error(e);
            return Optional.empty();
        }
    }

    public String getOrLoad(String key, Duration ttl, Supplier<String> loader) {
        return get(key).orElseGet(() -> {
            String v = loader.get();
            put(key, v, ttl);
            return v;
        });
    }

    public void put(String key, String value, Duration ttl) {
        try { redis.opsForValue().set(key, value, ttl); } catch (RuntimeException e) { error(e); }
    }

    public void evictPrefix(String prefix) {
        try {
            var keys = redis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (RuntimeException e) { error(e); }
    }

    public void evict(String key) {
        try { redis.delete(key); } catch (RuntimeException e) { error(e); }
    }

    /** Returns empty when the cache is unavailable so callers can consult the database. */
    public Optional<Boolean> isMember(String set, String member) {
        try { return Optional.ofNullable(redis.opsForSet().isMember(set, member)); } catch (RuntimeException e) { error(e); return Optional.empty(); }
    }

    public void addMember(String set, String member) {
        try { redis.opsForSet().add(set, member); } catch (RuntimeException e) { error(e); }
    }

    private void error(RuntimeException e) {
        meters.counter("streamsphere_cache_errors_total").increment();
        log.debug("Cache unavailable, falling back: {}", e.toString());
    }
}
