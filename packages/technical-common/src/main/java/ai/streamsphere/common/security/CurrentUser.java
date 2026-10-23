package ai.streamsphere.common.security;

import ai.streamsphere.common.web.ApiException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Accessors for the authenticated caller. The subject ("sub") is the stable user id across services. */
public final class CurrentUser {
    private CurrentUser() {}

    public static Optional<Jwt> jwt() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof JwtAuthenticationToken t ? Optional.of(t.getToken()) : Optional.empty();
    }

    public static String id() {
        return jwt().map(Jwt::getSubject)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"));
    }

    public static Optional<String> idIfPresent() { return jwt().map(Jwt::getSubject); }

    public static String displayName() {
        return jwt().map(j -> Optional.ofNullable(j.getClaimAsString("name"))
                .orElse(Optional.ofNullable(j.getClaimAsString("preferred_username")).orElse("user"))).orElse("anonymous");
    }

    public static boolean hasRole(String role) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_" + role));
    }

    public static boolean isStaff() { return hasRole("moderator") || hasRole("admin"); }

    /** Raw bearer token for relaying the user's identity to a downstream service. */
    public static String bearer() {
        return jwt().map(Jwt::getTokenValue)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"));
    }
}
