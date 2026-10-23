package ai.streamsphere.search;

import ai.streamsphere.common.security.ServiceTokenClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls the recommendations service's CPU embedding endpoint. Absence → keyword-only search (graceful). */
@Component
public class Embedder {
    private static final Logger log = LoggerFactory.getLogger(Embedder.class);
    public record Embedding(String model, float[] vector) {}

    private final RestClient http;
    private final ServiceTokenClient tokens;

    public Embedder(RestClient.Builder b, ServiceTokenClient tokens, @Value("${streamsphere.services.recommendations}") String url) {
        this.http = b.baseUrl(url).build();
        this.tokens = tokens;
    }

    @SuppressWarnings("unchecked")
    public Optional<Embedding> embed(String text) {
        try {
            Map<String, Object> r = http.post().uri("/internal/embed").header("Authorization", "Bearer " + tokens.token())
                    .body(Map.of("texts", List.of(text))).retrieve().body(Map.class);
            List<Number> v = ((List<List<Number>>) r.get("vectors")).getFirst();
            float[] f = new float[v.size()];
            for (int i = 0; i < f.length; i++) f[i] = v.get(i).floatValue();
            return Optional.of(new Embedding((String) r.get("model"), f));
        } catch (RuntimeException e) {
            log.warn("Embedding unavailable, falling back to keyword search: {}", e.toString());
            return Optional.empty();
        }
    }
}
