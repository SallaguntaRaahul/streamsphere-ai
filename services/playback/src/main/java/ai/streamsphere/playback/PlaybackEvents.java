package ai.streamsphere.playback;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Takedown/deletion → immediate revocation of the title and all its open sessions. */
@Component
public class PlaybackEvents implements EventHandler {
    private final Revocations revocations;
    private final Upstreams upstreams;

    public PlaybackEvents(Revocations revocations, Upstreams upstreams) {
        this.revocations = revocations;
        this.upstreams = upstreams;
    }

    @Override
    public Set<String> routingKeys() { return Set.of("ContentRemoved.v1"); }

    @Override
    public void handle(EventEnvelope e) {
        UUID titleId = UUID.fromString(e.payload().get("titleId").asString());
        revocations.revokeTitle(titleId, e.payload().path("reason").asString("removed"));
        if (e.payload().hasNonNull("mediaAssetId")) upstreams.evict(UUID.fromString(e.payload().get("mediaAssetId").asString()));
    }
}
