package ai.streamsphere.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceAndRolesTest {
    static Jwt jwt(List<String> aud, Map<String, Object> realmAccess) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u1").audience(aud)
                .claim("realm_access", realmAccess).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Test
    void tokenWithoutRequiredAudienceIsRejected() {
        var v = SecurityAutoConfiguration.audienceValidator("streamsphere-api");
        assertThat(v.validate(jwt(List.of("account"), Map.of())).hasErrors()).isTrue();
        assertThat(v.validate(jwt(List.of("account", "streamsphere-api"), Map.of())).hasErrors()).isFalse();
    }

    @Test
    void realmRolesBecomePrefixedAuthorities() {
        var auth = SecurityAutoConfiguration.realmRoles(jwt(List.of("streamsphere-api"), Map.of("roles", List.of("moderator", "viewer"))));
        assertThat(auth).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder("ROLE_moderator", "ROLE_viewer");
    }

    @Test
    void missingRealmAccessYieldsNoAuthorities() {
        assertThat(SecurityAutoConfiguration.realmRoles(jwt(List.of("streamsphere-api"), Map.of()))).isEmpty();
    }
}
