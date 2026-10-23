package ai.streamsphere.common.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires outbox relay + event consumer for a service named by streamsphere.service-name.
 * Topology per service: &lt;svc&gt;.events (bound to its routing keys), &lt;svc&gt;.retry (TTL → back to
 * &lt;svc&gt;.events), &lt;svc&gt;.dlq (via streamsphere.dlx).
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"})
@ConditionalOnClass({RabbitTemplate.class, JdbcClient.class})
@ConditionalOnProperty(name = "streamsphere.messaging.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json) {
        return new OutboxWriter(jdbc, json);
    }

    @Bean
    @ConditionalOnBean({JdbcClient.class, RabbitTemplate.class})
    OutboxRelay outboxRelay(JdbcClient jdbc, RabbitTemplate rabbit, PlatformTransactionManager tm, JsonMapper json,
                            @Value("${streamsphere.service-name}") String service, MeterRegistry meters) {
        return new OutboxRelay(jdbc, rabbit, new TransactionTemplate(tm), json, service, meters);
    }

    @Bean
    Declarables streamsphereTopology(@Value("${streamsphere.service-name}") String service, List<EventHandler> handlers) {
        List<Declarable> d = new ArrayList<>();
        TopicExchange events = new TopicExchange(MessagingTopology.EVENTS_EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(MessagingTopology.DLX, true, false);
        d.add(events);
        d.add(dlx);
        if (!handlers.isEmpty()) {
            Queue main = QueueBuilder.durable(MessagingTopology.queue(service))
                    .withArguments(Map.of("x-dead-letter-exchange", MessagingTopology.DLX,
                            "x-dead-letter-routing-key", MessagingTopology.dlq(service)))
                    .build();
            Queue retry = QueueBuilder.durable(MessagingTopology.retryQueue(service))
                    .withArguments(Map.of("x-dead-letter-exchange", "",
                            "x-dead-letter-routing-key", MessagingTopology.queue(service)))
                    .build();
            Queue dlq = QueueBuilder.durable(MessagingTopology.dlq(service)).build();
            d.add(main);
            d.add(retry);
            d.add(dlq);
            d.add(BindingBuilder.bind(dlq).to(dlx).with(MessagingTopology.dlq(service)));
            for (EventHandler h : handlers) {
                for (String key : h.routingKeys()) {
                    Binding b = BindingBuilder.bind(main).to(events).with(key);
                    d.add(b);
                }
            }
        }
        return new Declarables(d);
    }

    @Bean
    @ConditionalOnBean(EventHandler.class)
    SimpleMessageListenerContainer streamsphereEventContainer(ConnectionFactory cf, List<EventHandler> handlers, JdbcClient jdbc,
            PlatformTransactionManager tm, RabbitTemplate rabbit, JsonMapper json, MeterRegistry meters,
            @Value("${streamsphere.service-name}") String service,
            @Value("${streamsphere.messaging.max-attempts:5}") int maxAttempts,
            @Value("${streamsphere.messaging.base-delay-ms:1000}") long baseDelay,
            @Value("${streamsphere.messaging.concurrency:1}") int concurrency) {
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(cf);
        c.setQueueNames(MessagingTopology.queue(service));
        c.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        c.setPrefetchCount(10);
        c.setConcurrentConsumers(concurrency);
        c.setMissingQueuesFatal(false);
        c.setMessageListener(new EventConsumer(service, handlers, jdbc, new TransactionTemplate(tm), rabbit, json, meters,
                maxAttempts, baseDelay));
        return c;
    }
}
