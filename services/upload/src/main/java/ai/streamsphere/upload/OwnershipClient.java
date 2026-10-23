package ai.streamsphere.upload;

import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Verifies title ownership and rights attestation through the owning services' APIs. */
@Component
public class OwnershipClient {
    private final RestClient catalog;
    private final RestClient creator;

    public OwnershipClient(RestClient.Builder b, @Value("${streamsphere.services.catalog}") String catalogUrl,
                           @Value("${streamsphere.services.creator}") String creatorUrl) {
        this.catalog = b.clone().baseUrl(catalogUrl).build();
        this.creator = b.clone().baseUrl(creatorUrl).build();
    }

    public Map<String, Object> ownedTitle(UUID titleId) {
        try {
            return catalog.get().uri("/api/v1/creator/titles/{id}", titleId).header("Authorization", "Bearer " + CurrentUser.bearer())
                    .retrieve().body(new ParameterizedTypeReference<>() {});
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("Title");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "Catalog unavailable");
        }
    }

    public boolean attested(UUID titleId) {
        try {
            List<?> l = creator.get().uri("/api/v1/creators/me/attestations?titleId={t}", titleId)
                    .header("Authorization", "Bearer " + CurrentUser.bearer()).retrieve().body(List.class);
            return l != null && !l.isEmpty();
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "Creator service unavailable");
        }
    }
}
