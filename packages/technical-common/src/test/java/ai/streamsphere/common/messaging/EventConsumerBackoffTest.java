package ai.streamsphere.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.amqp.core.MessageProperties;
import org.junit.jupiter.api.Test;

class EventConsumerBackoffTest {
    final EventConsumer consumer = new EventConsumer("svc", List.of(), null, null, null, null, new SimpleMeterRegistry(), 5, 1000);

    @RepeatedTest(50)
    void backoffIsJitteredWithinExponentialBoundsAndCapped() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            long cap = Math.min(60_000L, 1000L * (1L << attempt));
            long d = consumer.backoff(attempt);
            assertThat(d).isBetween(Math.max(100, cap / 2), cap);
        }
    }

    @Test
    void attemptHeaderDefaultsToZero() {
        MessageProperties p = new MessageProperties();
        assertThat(EventConsumer.attemptOf(p)).isZero();
        p.setHeader(EventConsumer.ATTEMPT_HEADER, 3);
        assertThat(EventConsumer.attemptOf(p)).isEqualTo(3);
    }

    @Test
    void routingKeyCombinesTypeAndVersion() {
        var e = new EventEnvelope(null, "UploadCompleted", 1, "a", null, null, "upload", null);
        assertThat(e.routingKey()).isEqualTo("UploadCompleted.v1");
    }
}
