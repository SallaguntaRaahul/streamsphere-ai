package ai.streamsphere.playback;

import ai.streamsphere.common.security.ServiceTokenClient;
import ai.streamsphere.common.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Service-token calls to catalog and media internal APIs. Manifests are immutable per asset → short local cache. */
@Component
public class Upstreams {
    public record TitleInfo(UUID id, String creatorId, String editorialState, UUID mediaAssetId, String mediaState) {}
    public record Rendition(String name, int width, int height, int bandwidth, String codecs, String playlistKey, int segmentCount) {}
    public record Caption(String language, String label, String objectKey, boolean isDefault) {}
    public record Manifest(UUID assetId, UUID titleId, String creatorId, int assetVersion, String state, boolean published,
                           boolean removed, String bucket, String outputPrefix, int segmentSeconds, Integer durationSeconds,
                           List<Rendition> renditions, List<Caption> captions) {}

    private final RestClient catalog;
    private final RestClient media;
    private final ServiceTokenClient tokens;
    private final Map<UUID, Map.Entry<Instant, Manifest>> manifests = new ConcurrentHashMap<>();

    public Upstreams(RestClient.Builder b, ServiceTokenClient tokens, @Value("${streamsphere.services.catalog}") String catalogUrl,
                     @Value("${streamsphere.services.media}") String mediaUrl) {
        this.catalog = b.clone().baseUrl(catalogUrl).build();
        this.media = b.clone().baseUrl(mediaUrl).build();
        this.tokens = tokens;
    }

    public TitleInfo title(UUID id) {
        try {
            return catalog.get().uri("/api/v1/internal/titles/{id}", id).header("Authorization", "Bearer " + tokens.token())
                    .retrieve().body(TitleInfo.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("Title");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "Catalog unavailable");
        }
    }

    public Manifest manifest(UUID assetId) {
        var hit = manifests.get(assetId);
        if (hit != null && hit.getKey().isAfter(Instant.now())) return hit.getValue();
        try {
            Manifest m = media.get().uri("/api/v1/internal/assets/{id}/manifest", assetId).header("Authorization", "Bearer " + tokens.token())
                    .retrieve().body(Manifest.class);
            manifests.put(assetId, Map.entry(Instant.now().plus(Duration.ofSeconds(30)), m));
            return m;
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("Media");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "Media service unavailable");
        }
    }

    public void evict(UUID assetId) { manifests.remove(assetId); }
}
