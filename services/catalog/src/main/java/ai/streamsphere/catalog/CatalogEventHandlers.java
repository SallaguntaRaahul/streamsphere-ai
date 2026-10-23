package ai.streamsphere.catalog;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class CatalogEventHandlers implements EventHandler {
    private final TitleService titles;

    public CatalogEventHandlers(TitleService titles) { this.titles = titles; }

    @Override
    public Set<String> routingKeys() {
        return Set.of("MediaProcessingStarted.v1", "MediaReady.v1", "MediaProcessingFailed.v1",
                "ContentApproved.v1", "ContentRejected.v1", "ContentRemoved.v1");
    }

    @Override
    public void handle(EventEnvelope e) {
        JsonNode p = e.payload();
        UUID titleId = UUID.fromString(p.get("titleId").asString());
        switch (e.eventType()) {
            case "MediaProcessingStarted" -> titles.onMedia(titleId, "PROCESSING", uuid(p, "assetId"), integer(p, "version"), null, null);
            case "MediaReady" -> titles.onMedia(titleId, "READY", uuid(p, "assetId"), integer(p, "version"),
                    integer(p, "durationSeconds"), p.path("hasPoster").asBoolean(false));
            case "MediaProcessingFailed" -> titles.onMedia(titleId, "FAILED", uuid(p, "assetId"), integer(p, "version"), null, null);
            case "ContentApproved" -> titles.onModerationDecision(titleId, true, null, p.path("appeal").asBoolean(false));
            case "ContentRejected" -> titles.onModerationDecision(titleId, false, p.path("reason").asString(null), p.path("appeal").asBoolean(false));
            case "ContentRemoved" -> { if (!"catalog".equals(e.producer())) titles.onTakedown(titleId, p.path("reason").asString("removed")); }
            default -> { }
        }
    }

    private static UUID uuid(JsonNode p, String f) { return p.hasNonNull(f) ? UUID.fromString(p.get(f).asString()) : null; }
    private static Integer integer(JsonNode p, String f) { return p.hasNonNull(f) ? p.get(f).asInt() : null; }
}
