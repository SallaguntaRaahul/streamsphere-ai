package ai.streamsphere.common.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Versioned event envelope; see contracts/events/envelope.schema.json. */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        String producer,
        JsonNode payload) {

    /** Routing key, e.g. "UploadCompleted.v1". */
    public String routingKey() { return eventType + ".v" + eventVersion; }
}
