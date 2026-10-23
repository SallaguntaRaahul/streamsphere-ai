package ai.streamsphere.gateway;

import ai.streamsphere.common.security.SecurityAutoConfiguration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Edge policy. Downstream services still authorize every request themselves; the gateway additionally
 * rejects invalid bearer tokens early, never exposes /api/v1/internal/**, and protects admin endpoints.
 */
@Configuration
public class GatewaySecurity {
    @Bean
    SecurityFilterChain gatewayChain(HttpSecurity http, @Value("${streamsphere.cors.origins}") List<String> origins) throws Exception {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("ETag", "X-Correlation-Id", "Location"));
        cors.setMaxAge(600L);
        var src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cors);
        http.csrf(c -> c.disable())
            .cors(c -> c.configurationSource(src))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/api/v1/internal/**", "/api/*/internal/**").denyAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("admin")
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .anyRequest().permitAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(SecurityAutoConfiguration.jwtAuthenticationConverter())))
            .headers(h -> h
                    .contentTypeOptions(c -> { })
                    .frameOptions(f -> f.deny())
                    .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31536000)));
        return http.build();
    }
}
