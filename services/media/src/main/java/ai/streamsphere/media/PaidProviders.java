package ai.streamsphere.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Paid managed-video adapters (ADR-0016): AWS Elemental MediaConvert (transcoding only — not a hosting
 * replacement), Mux and Cloudflare Stream (transcode + delivery). They are wired behind TWO flags and refuse
 * to construct otherwise, so a misconfiguration can never start spending. No real API calls are implemented
 * or tested: doing so requires the owner's sandbox budget approval. Assets record their provider, and existing
 * assets are never migrated silently.
 */
@Configuration
public class PaidProviders {
    public static final java.util.Set<String> PAID = java.util.Set.of("mediaconvert", "mux", "cloudflare-stream");

    /** Guard used by every paid adapter; unit-tested. */
    public static void requireApproval(String provider, boolean paidEnabled, boolean providerEnabled) {
        if (!PAID.contains(provider)) throw new IllegalArgumentException("not a paid provider: " + provider);
        if (!paidEnabled || !providerEnabled) {
            throw new IllegalStateException(provider + " is a PAID provider and is disabled (PAID_PROVIDERS_ENABLED=false or provider flag off)");
        }
    }

    record PaidLauncher(String name) implements TranscodeLauncher {
        public String launch(Contracts.TranscodeRequested request) {
            throw new UnsupportedOperationException(name + " adapter is a disabled stub: no live API integration without an approved sandbox budget");
        }
    }

    @Bean
    @ConditionalOnExpression("'${streamsphere.media.launcher:queue}' matches 'mediaconvert|mux|cloudflare-stream'")
    TranscodeLauncher paidLauncher(@Value("${streamsphere.media.launcher}") String provider,
                                   @Value("${streamsphere.paid-providers.enabled:false}") boolean paid,
                                   @Value("${streamsphere.paid-providers.${streamsphere.media.launcher}.enabled:false}") boolean flag) {
        requireApproval(provider, paid, flag); // fails application startup when not explicitly approved
        return new PaidLauncher(provider);
    }
}
