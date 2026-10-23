package ai.streamsphere.common.security;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** OAuth2 client-credentials token for service-to-service calls (Keycloak service account with role "service"). */
public class ServiceTokenClient {
    private final RestClient http;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ServiceTokenClient(RestClient.Builder builder, String tokenUri, String clientId, String clientSecret) {
        this.http = builder.build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public synchronized String token() {
        if (token != null && Instant.now().isBefore(expiresAt.minusSeconds(30))) return token;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        if (body == null || body.get("access_token") == null) throw new IllegalStateException("No access_token from " + tokenUri);
        token = (String) body.get("access_token");
        expiresAt = Instant.now().plusSeconds(((Number) body.getOrDefault("expires_in", 60)).longValue());
        return token;
    }
}
