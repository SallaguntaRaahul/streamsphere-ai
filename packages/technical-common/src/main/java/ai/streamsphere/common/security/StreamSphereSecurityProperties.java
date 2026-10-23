package ai.streamsphere.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer      expected "iss" claim (the public Keycloak URL browsers use)
 * @param jwkSetUri   where this service fetches signing keys (may be an internal URL)
 * @param audience    required "aud" value
 * @param publicPaths ant-style paths that need no token (actuator health etc. are always public)
 */
@ConfigurationProperties("streamsphere.security")
public record StreamSphereSecurityProperties(String issuer, String jwkSetUri, String audience, List<String> publicPaths) {
    public StreamSphereSecurityProperties {
        if (audience == null) audience = "streamsphere-api";
        if (publicPaths == null) publicPaths = List.of();
    }
}
