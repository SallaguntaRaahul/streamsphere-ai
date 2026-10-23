package ai.streamsphere.common.messaging;

import ai.streamsphere.common.web.CorrelationIdFilter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event into the service's own outbox_events table. MUST be called inside the business
 * transaction (enforced with MANDATORY propagation) so state change and event commit atomically.
 */
public class OutboxWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String eventType, int version, String aggregateId, Object payload) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into outbox_events(id, event_type, event_version, aggregate_id, payload, correlation_id, occurred_at)
                values (:id, :type, :version, :agg, cast(:payload as jsonb), :cid, :at)""")
            .param("id", id).param("type", eventType).param("version", version).param("agg", aggregateId)
            .param("payload", json.writeValueAsString(payload)).param("cid", CorrelationIdFilter.current())
            .param("at", Timestamp.from(Instant.now()))
            .update();
        return id;
    }
}
