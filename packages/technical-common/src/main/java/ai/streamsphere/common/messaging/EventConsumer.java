package ai.streamsphere.common.messaging;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual-ack consumer: parse → (tx: inbox dedup + handler) → ack. On failure the message is copied to
 * the service retry queue with exponential backoff + full jitter (per-message TTL, dead-lettered back
 * to the main queue); after maxAttempts it is rejected into the DLQ. Unparseable messages go straight
 * to the DLQ (poison).
 */
public class EventConsumer implements ChannelAwareMessageListener {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    static final String ATTEMPT_HEADER = "x-attempt";

    private final String service;
    private final Map<String, EventHandler> handlers;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final int maxAttempts;
    private final long baseDelayMs;

    public EventConsumer(String service, List<EventHandler> handlers, JdbcClient jdbc, TransactionTemplate tx,
                         RabbitTemplate rabbit, JsonMapper json, MeterRegistry meters, int maxAttempts, long baseDelayMs) {
        this.service = service;
        this.handlers = handlers.stream().flatMap(h -> h.routingKeys().stream().map(k -> Map.entry(k, h)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        this.jdbc = jdbc;
        this.tx = tx;
        this.rabbit = rabbit;
        this.json = json;
        this.meters = meters;
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
    }

    public java.util.Set<String> routingKeys() { return handlers.keySet(); }

    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        EventEnvelope env;
        try {
            env = json.readValue(message.getBody(), EventEnvelope.class);
        } catch (RuntimeException e) {
            log.error("Poison message on {} (unparseable); dead-lettering", service);
            meters.counter("streamsphere_events_dlq_total", "service", service, "reason", "poison").increment();
            channel.basicReject(tag, false);
            return;
        }
        if (env.correlationId() != null) MDC.put("correlationId", env.correlationId());
        try {
            EventHandler handler = handlers.get(env.routingKey());
            if (handler == null) {
                channel.basicAck(tag, false); // not ours (binding changed) — drop
                return;
            }
            Boolean processed = tx.execute(s -> {
                int inserted = jdbc.sql("insert into inbox_deduplication(consumer, event_id) values (:c, :id) on conflict do nothing")
                        .param("c", service).param("id", env.eventId()).update();
                if (inserted == 0) return false;
                handler.handle(env);
                return true;
            });
            meters.counter("streamsphere_events_consumed_total", "service", service, "type", env.eventType(),
                    "outcome", Boolean.TRUE.equals(processed) ? "processed" : "duplicate").increment();
            channel.basicAck(tag, false);
        } catch (Exception e) {
            int attempt = attemptOf(message.getMessageProperties()) + 1;
            if (attempt >= maxAttempts) {
                log.error("Event {} {} failed {} times; dead-lettering: {}", env.routingKey(), env.eventId(), attempt, e.toString());
                meters.counter("streamsphere_events_dlq_total", "service", service, "reason", "exhausted").increment();
                channel.basicReject(tag, false);
            } else {
                long delay = backoff(attempt);
                log.warn("Event {} {} failed (attempt {}), retrying in {} ms: {}", env.routingKey(), env.eventId(), attempt, delay, e.toString());
                MessageProperties p = message.getMessageProperties();
                p.setHeader(ATTEMPT_HEADER, attempt);
                p.setExpiration(Long.toString(delay));
                rabbit.send("", MessagingTopology.retryQueue(service), new Message(message.getBody(), p));
                meters.counter("streamsphere_events_retried_total", "service", service).increment();
                channel.basicAck(tag, false);
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    static int attemptOf(MessageProperties p) {
        Object v = p.getHeaders().get(ATTEMPT_HEADER);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** Exponential backoff with full jitter, capped at 60 s. */
    long backoff(int attempt) {
        long cap = Math.min(60_000L, baseDelayMs * (1L << Math.min(attempt, 16)));
        return Math.max(100, ThreadLocalRandom.current().nextLong(cap / 2, cap + 1));
    }

    static Function<String, String> noop() { return s -> s; }
}
