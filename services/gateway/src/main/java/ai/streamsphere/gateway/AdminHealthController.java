package ai.streamsphere.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Admin-only (enforced in GatewaySecurity) aggregated readiness of every service. */
@RestController
@org.springframework.boot.context.properties.EnableConfigurationProperties(AdminHealthController.Upstreams.class)
public class AdminHealthController {
    @ConfigurationProperties("streamsphere")
    public record Upstreams(Map<String, String> upstreams) {}

    private final Map<String, String> upstreams;
    private final RestClient http;

    public AdminHealthController(Upstreams u) {
        this.upstreams = u.upstreams();
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(1));
        rf.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @GetMapping("/api/v1/admin/health")
    public Map<String, Object> health() {
        Map<String, CompletableFuture<Map<String, Object>>> futures = new LinkedHashMap<>();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            upstreams.forEach((name, url) -> futures.put(name, CompletableFuture.supplyAsync(() -> probe(url), ex)));
            Map<String, Object> out = new LinkedHashMap<>();
            futures.forEach((k, f) -> out.put(k, f.join()));
            return Map.of("checkedAt", Instant.now().toString(), "services", out);
        }
    }

    private Map<String, Object> probe(String base) {
        long t = System.nanoTime();
        try {
            String body = http.get().uri(base + "/actuator/health").retrieve().body(String.class);
            return Map.of("status", body != null && body.contains("\"UP\"") ? "UP" : "DEGRADED", "latencyMs", (System.nanoTime() - t) / 1_000_000);
        } catch (RuntimeException e) {
            return Map.of("status", "DOWN", "latencyMs", (System.nanoTime() - t) / 1_000_000, "error", e.getClass().getSimpleName());
        }
    }
}
