package ai.streamsphere.common.observability;

import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 4 exports traces/metrics via OTLP but does not attach a Logback appender for logs. When
 * management.logging.export.otlp.enabled=true, this installs the OpenTelemetry appender on the root logger so
 * logs (with traceId/spanId and correlationId) flow to the collector → Loki.
 */
@AutoConfiguration
@ConditionalOnClass({OpenTelemetryAppender.class, OpenTelemetry.class, LoggerContext.class})
public class OtelLogsAutoConfiguration {
    @Bean
    ApplicationListener<ApplicationReadyEvent> streamsphereOtelLogAppender(ObjectProvider<OpenTelemetry> otel,
            @Value("${management.logging.export.otlp.enabled:false}") boolean enabled) {
        return event -> {
            OpenTelemetry sdk = otel.getIfAvailable();
            if (!enabled || sdk == null || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) return;
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setContext(ctx);
            appender.setName("otel-logs");
            appender.setCaptureMdcAttributes("correlationId");
            appender.start();
            ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
            OpenTelemetryAppender.install(sdk);
        };
    }
}
