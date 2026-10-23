package ai.streamsphere.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"streamsphere.security.issuer=http://issuer.test", "streamsphere.security.jwk-set-uri=http://issuer.test/jwks", "spring.rabbitmq.port=1"})
@AutoConfigureMockMvc
class NotificationsIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired MockMvc mvc;
    @Autowired List<EventHandler> handlers;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @Test
    void redeliveredEventCreatesOneNotificationAndEmailIsSkippedByDefault() throws Exception {
        var e = new EventEnvelope(UUID.randomUUID(), "MediaReady", 1, "t", Instant.now(), "c", "media",
                json.valueToTree(Map.of("titleId", UUID.randomUUID(), "creatorId", "creator-9", "assetId", UUID.randomUUID())));
        EventHandler h = handlers.getFirst();
        h.handle(e);
        h.handle(e); // at-least-once redelivery
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject("creator-9"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].kind").value("MEDIA_READY"));
        mvc.perform(get("/api/v1/notifications/unread-count").with(jwt().jwt(j -> j.subject("creator-9")))).andExpect(jsonPath("$.unread").value(1));
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject("someone-else")))).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(jdbc.sql("select channel || ':' || status from delivery_attempts order by channel").query(String.class).list())
                .containsExactly("email:SKIPPED", "in-app:DELIVERED");
    }
}
