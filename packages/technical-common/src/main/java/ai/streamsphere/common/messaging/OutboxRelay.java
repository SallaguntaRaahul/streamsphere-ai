package ai.streamsphere.common.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Polls the outbox and publishes with publisher confirms. Rows are claimed with
 * FOR UPDATE SKIP LOCKED so several replicas can relay concurrently. A crash between publish and
 * commit causes a re-publish: delivery is at-least-once and consumers deduplicate by eventId.
 */
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final String producer;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(JdbcClient jdbc, RabbitTemplate rabbit, TransactionTemplate tx, JsonMapper json,
                       String producer, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.tx = tx;
        this.json = json;
        this.producer = producer;
        this.published = meters.counter("streamsphere_outbox_published_total");
        this.failed = meters.counter("streamsphere_outbox_publish_failures_total");
    }

    record Row(UUID id, String eventType, int eventVersion, String aggregateId, String payload, String correlationId, Timestamp occurredAt) {}

    @Scheduled(fixedDelayString = "${streamsphere.outbox.poll-ms:500}")
    public void relay() {
        try {
            Integer n;
            do {
                n = tx.execute(s -> relayBatch());
            } while (n != null && n == 50);
        } catch (RuntimeException e) {
            failed.increment();
            log.warn("Outbox relay batch failed; will retry: {}", e.getMessage());
        }
    }

    int relayBatch() {
        List<Row> rows = jdbc.sql("""
                select id, event_type, event_version, aggregate_id, payload::text as payload, correlation_id, occurred_at
                from outbox_events where published_at is null order by occurred_at limit 50 for update skip locked""")
            .query(Row.class).list();
        if (rows.isEmpty()) return 0;
        rabbit.invoke(ops -> {
            for (Row r : rows) {
                EventEnvelope env = new EventEnvelope(r.id(), r.eventType(), r.eventVersion(), r.aggregateId(),
                        r.occurredAt().toInstant(), r.correlationId(), producer, json.readValue(r.payload(), JsonNode.class));
                MessageProperties p = new MessageProperties();
                p.setContentType("application/json");
                p.setMessageId(r.id().toString());
                p.setType(env.routingKey());
                p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                p.setHeader("x-correlation-id", r.correlationId());
                ops.send(MessagingTopology.EVENTS_EXCHANGE, env.routingKey(),
                        new Message(json.writeValueAsString(env).getBytes(StandardCharsets.UTF_8), p));
            }
            ops.waitForConfirmsOrDie(10_000);
            return null;
        });
        Timestamp now = Timestamp.from(Instant.now());
        for (Row r : rows) {
            jdbc.sql("update outbox_events set published_at = :now where id = :id").param("now", now).param("id", r.id()).update();
        }
        published.increment(rows.size());
        return rows.size();
    }
}
