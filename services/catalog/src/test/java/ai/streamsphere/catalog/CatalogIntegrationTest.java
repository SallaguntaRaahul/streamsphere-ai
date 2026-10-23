package ai.streamsphere.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.streamsphere.common.messaging.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL (Flyway migrations) + real RabbitMQ: lifecycle, ownership, outbox → broker. */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
        "streamsphere.security.issuer=http://issuer.test", "streamsphere.security.jwk-set-uri=http://issuer.test/jwks",
        "streamsphere.services.creator=http://creator.test", "streamsphere.outbox.poll-ms=200",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1"}) // cache unreachable → must degrade gracefully
@AutoConfigureMockMvc
class CatalogIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.2-management-alpine").asCompatibleSubstituteFor("rabbitmq"));

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired CatalogEventHandlers handlers;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin admin;
    @Autowired JsonMapper json;
    @MockitoBean CreatorClient creators;

    static final String OWNER = "creator-1", OTHER = "creator-2";

    @Test
    void fullLifecycleWithOwnershipChecksAndOutbox() throws Exception {
        when(creators.requireRegisteredCreator()).thenReturn(Map.of("status", "ACTIVE", "displayName", "Cyrus"));
        when(creators.hasRightsAttestation(anyString())).thenReturn(true);
        // observe what the outbox relay publishes
        Queue probe = new Queue("test.probe", false, false, false);
        admin.declareQueue(probe);
        for (String key : new String[]{"ContentSubmitted.v1", "ContentPublished.v1"}) {
            admin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange("streamsphere.events")).with(key));
        }

        String body = mvc.perform(post("/api/v1/creator/titles").with(jwt().jwt(j -> j.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Harbor\",\"genres\":[\"drama\"],\"releaseYear\":2026}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.editorialState").value("DRAFT")).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asString());

        // optimistic locking: If-Match required and enforced
        mvc.perform(put("/api/v1/creator/titles/" + id).with(jwt().jwt(j -> j.subject(OWNER))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Harbor 2\"}")).andExpect(status().is(428));
        mvc.perform(put("/api/v1/creator/titles/" + id).with(jwt().jwt(j -> j.subject(OWNER))).header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Harbor 2\"}")).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/creator/titles/" + id).with(jwt().jwt(j -> j.subject(OWNER))).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Harbor 2\"}")).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));

        // BOLA: another creator can neither see nor act on it
        mvc.perform(get("/api/v1/creator/titles/" + id).with(jwt().jwt(j -> j.subject(OTHER)))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/creator/titles/" + id + "/submit").with(jwt().jwt(j -> j.subject(OTHER)))).andExpect(status().isNotFound());
        // unpublished title is invisible publicly
        mvc.perform(get("/api/v1/titles/" + id)).andExpect(status().isNotFound());

        // cannot submit before media is ready
        mvc.perform(post("/api/v1/creator/titles/" + id + "/submit").with(jwt().jwt(j -> j.subject(OWNER))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://streamsphere.dev/problems/media-not-ready"));

        UUID asset = UUID.randomUUID();
        handlers.handle(event("MediaReady", Map.of("titleId", id, "assetId", asset, "version", 1, "durationSeconds", 20, "hasPoster", true)));
        // an older asset version must not overwrite the newer one
        handlers.handle(event("MediaProcessingFailed", Map.of("titleId", id, "assetId", UUID.randomUUID(), "version", 0)));

        mvc.perform(post("/api/v1/creator/titles/" + id + "/submit").with(jwt().jwt(j -> j.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.editorialState").value("IN_REVIEW")).andExpect(jsonPath("$.mediaState").value("READY"));
        mvc.perform(post("/api/v1/creator/titles/" + id + "/publish").with(jwt().jwt(j -> j.subject(OWNER)))).andExpect(status().isConflict());

        handlers.handle(event("ContentApproved", Map.of("titleId", id)));
        handlers.handle(event("ContentApproved", Map.of("titleId", id))); // duplicate decision is harmless
        mvc.perform(post("/api/v1/creator/titles/" + id + "/publish").with(jwt().jwt(j -> j.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.editorialState").value("PUBLISHED"));

        mvc.perform(get("/api/v1/titles")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].posterPath").value("/api/v1/media/assets/" + asset + "/poster"));

        // outbox: both events committed with the state changes and relayed to the broker with confirms
        assertThat(jdbc.sql("select event_type from outbox_events order by occurred_at").query(String.class).list())
                .containsExactly("ContentSubmitted", "ContentPublished");
        Message first = rabbitTemplate.receive("test.probe", Duration.ofSeconds(10).toMillis());
        Message second = rabbitTemplate.receive("test.probe", Duration.ofSeconds(10).toMillis());
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(json.readTree(second.getBody()).get("eventType").asString()).isEqualTo("ContentPublished");
        assertThat(jdbc.sql("select count(*) from outbox_events where published_at is null").query(Integer.class).single()).isZero();

        // takedown from moderation removes it publicly
        handlers.handle(event("ContentRemoved", Map.of("titleId", id, "reason", "takedown: test"), "moderation"));
        mvc.perform(get("/api/v1/titles/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void anonymousCannotCreateAndValidationIsEnforced() throws Exception {
        mvc.perform(post("/api/v1/creator/titles").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/creator/titles").with(jwt().jwt(j -> j.subject(OWNER))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\",\"releaseYear\":1700,\"genres\":[\"<script>\"]}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/titles?genre=../../etc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/titles?cursor=garbage")).andExpect(status().isBadRequest());
    }

    private EventEnvelope event(String type, Map<String, Object> payload) { return event(type, payload, "test"); }

    private EventEnvelope event(String type, Map<String, Object> payload, String producer) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, "x", Instant.now(), "cid", producer, json.valueToTree(payload));
    }
}
