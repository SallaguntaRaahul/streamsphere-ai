package ai.streamsphere.catalog;

import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Talks to the creator service API (never its database), relaying the caller's token. */
@Component
public class CreatorClient {
    private final RestClient http;

    public CreatorClient(RestClient.Builder b, @Value("${streamsphere.services.creator}") String base) {
        this.http = b.baseUrl(base).build();
    }

    public Map<String, Object> requireRegisteredCreator() {
        try {
            Map<String, Object> me = http.get().uri("/api/v1/creators/me").header("Authorization", "Bearer " + CurrentUser.bearer())
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            if (me == null || !"ACTIVE".equals(me.get("status"))) throw ApiException.forbidden("Creator account is not active");
            return me;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "creator-registration-required", "Register as a creator first");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "Creator service unavailable");
        }
    }

    public boolean hasRightsAttestation(String titleId) {
        try {
            List<?> list = http.get().uri("/api/v1/creators/me/attestations?titleId={t}", titleId)
                    .header("Authorization", "Bearer " + CurrentUser.bearer()).retrieve().body(List.class);
            return list != null && !list.isEmpty();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "Creator service unavailable");
        }
    }
}
