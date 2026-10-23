package ai.streamsphere.worker;

import ai.streamsphere.common.storage.StorageProvider;
import ai.streamsphere.worker.Messages.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two modes, same image:
 *  --mode=consumer (Compose): consumes TranscodeRequested.v1 with prefetch 1 (bounded concurrency).
 *  --mode=oneshot  (Kubernetes Job): runs JOB_PAYLOAD once, reports, exits.
 * Results are published straight to the events exchange with publisher confirms; media deduplicates by jobId.
 */
@SpringBootApplication
public class WorkerApplication {
    private static final Logger log = LoggerFactory.getLogger(WorkerApplication.class);
    static final String EXCHANGE = "streamsphere.events";
    static final String QUEUE = "ffmpeg-worker.requests";

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(WorkerApplication.class, args);
        if (ctx.getEnvironment().getProperty("mode", "consumer").equals("oneshot")) {
            System.exit(SpringApplication.exit(ctx));
        }
    }

    @Bean
    Transcoder transcoder(StorageProvider storage, JsonMapper json, @Value("${worker.work-dir:/work}") String work,
                          @Value("${worker.fault-injection:}") String fault) {
        return new Transcoder(storage, json, Path.of(work), fault);
    }

    @Bean
    Declarables workerTopology() {
        TopicExchange ex = new TopicExchange(EXCHANGE, true, false);
        Queue q = QueueBuilder.durable(QUEUE).withArguments(Map.of("x-dead-letter-exchange", "streamsphere.dlx",
                "x-dead-letter-routing-key", "ffmpeg-worker.dlq")).build();
        Queue dlq = QueueBuilder.durable("ffmpeg-worker.dlq").build();
        return new Declarables(ex, new DirectExchange("streamsphere.dlx", true, false), q, dlq,
                BindingBuilder.bind(q).to(ex).with("TranscodeRequested.v1"),
                BindingBuilder.bind(dlq).to(new DirectExchange("streamsphere.dlx")).with("ffmpeg-worker.dlq"));
    }

    @Bean
    Reporter reporter(RabbitTemplate rabbit, JsonMapper json) { return new Reporter(rabbit, json); }

    @Bean
    ApplicationRunner oneshot(@Value("${mode:consumer}") String mode, Transcoder t, Reporter reporter, JsonMapper json) {
        return (ApplicationArguments args) -> {
            if (!mode.equals("oneshot")) return;
            String payload = System.getenv("JOB_PAYLOAD");
            TranscodeRequested req = json.readValue(Base64.getDecoder().decode(payload), TranscodeRequested.class);
            execute(req, t, reporter);
        };
    }

    @Bean
    SimpleMessageListenerContainer consumer(@Value("${mode:consumer}") String mode, ConnectionFactory cf, Transcoder t,
                                            Reporter reporter, JsonMapper json) {
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(cf);
        c.setQueueNames(QUEUE);
        c.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        c.setPrefetchCount(1);
        c.setConcurrentConsumers(1);
        c.setMissingQueuesFatal(false);
        c.setAutoStartup(mode.equals("consumer"));
        c.setMessageListener((ChannelAwareMessageListener) (msg, ch) -> {
            long tag = msg.getMessageProperties().getDeliveryTag();
            TranscodeRequested req;
            try {
                JsonNode env = json.readTree(msg.getBody());
                req = json.treeToValue(env.get("payload"), TranscodeRequested.class);
            } catch (RuntimeException e) {
                log.error("Poison transcode request; dead-lettering");
                ch.basicReject(tag, false);
                return;
            }
            execute(req, t, reporter);
            ch.basicAck(tag, false); // result (success or failure) is reported; media owns retries
        });
        return c;
    }

    static void execute(TranscodeRequested req, Transcoder t, Reporter reporter) {
        log.info("Transcode job {} asset {} attempt {} starting", req.jobId(), req.assetId(), req.attempt());
        reporter.publish("TranscodeStarted", req.assetId(), new TranscodeStarted(req.jobId(), req.assetId()));
        try {
            TranscodeCompleted done = t.run(req);
            reporter.publish("TranscodeCompleted", req.assetId(), done);
            log.info("Transcode job {} done in {} s", req.jobId(), Math.round(done.wallSeconds()));
        } catch (Proc.ProcFailure e) {
            log.warn("Transcode job {} failed (retryable={}): {}", req.jobId(), e.retryable, e.getMessage());
            reporter.publish("TranscodeFailed", req.assetId(), new TranscodeFailed(req.jobId(), req.assetId(), e.getMessage(), e.retryable));
        } catch (Exception e) {
            log.error("Transcode job {} crashed", req.jobId(), e);
            reporter.publish("TranscodeFailed", req.assetId(), new TranscodeFailed(req.jobId(), req.assetId(), e.toString(), true));
        }
    }

    /** Publishes envelopes with confirms. */
    public static class Reporter {
        private final RabbitTemplate rabbit;
        private final JsonMapper json;

        Reporter(RabbitTemplate rabbit, JsonMapper json) {
            this.rabbit = rabbit;
            this.json = json;
        }

        void publish(String type, UUID aggregate, Object payload) {
            UUID id = UUID.randomUUID();
            Map<String, Object> env = Map.of("eventId", id, "eventType", type, "eventVersion", 1, "aggregateId", aggregate.toString(),
                    "occurredAt", Instant.now().toString(), "correlationId", id.toString(), "producer", "ffmpeg-worker",
                    "payload", json.valueToTree(payload));
            MessageProperties p = new MessageProperties();
            p.setContentType("application/json");
            p.setMessageId(id.toString());
            p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            rabbit.invoke(ops -> {
                ops.send(EXCHANGE, type + ".v1", new Message(json.writeValueAsString(env).getBytes(StandardCharsets.UTF_8), p));
                ops.waitForConfirmsOrDie(10_000);
                return null;
            });
        }
    }
}
