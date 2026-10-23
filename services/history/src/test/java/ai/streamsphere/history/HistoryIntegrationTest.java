package ai.streamsphere.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"streamsphere.security.issuer=http://issuer.test", "streamsphere.security.jwk-set-uri=http://issuer.test/jwks",
        "spring.rabbitmq.port=1", "streamsphere.outbox.poll-ms=3600000"})
@AutoConfigureMockMvc
class HistoryIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    void progress(String user, UUID t, int pos, int dur) throws Exception {
        mvc.perform(put("/api/v1/history/progress").with(jwt().jwt(j -> j.subject(user))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titleId\":\"" + t + "\",\"positionSeconds\":" + pos + ",\"durationSeconds\":" + dur + "}")).andExpect(status().isOk());
    }

    @Test
    void milestonesEmitOnceAndCompletionOnce() throws Exception {
        UUID t = UUID.randomUUID();
        progress("u1", t, 10, 100);   // 0 %
        progress("u1", t, 30, 100);   // crosses 25 %
        progress("u1", t, 35, 100);   // same bucket → no event
        progress("u1", t, 30, 100);   // rewind → no duplicate event
        progress("u1", t, 60, 100);   // 50 %
        progress("u1", t, 95, 100);   // completed
        progress("u1", t, 99, 100);   // still completed → no duplicate
        var events = jdbc.sql("select event_type || ':' || coalesce(payload->>'milestonePercent','') from outbox_events where payload->>'userId' = 'u1' order by occurred_at")
                .query(String.class).list();
        // 60 % → 95 % crosses the 75 % bucket, then completes
        assertThat(events).containsExactly("PlaybackProgressed:25", "PlaybackProgressed:50", "PlaybackProgressed:75", "PlaybackCompleted:");
        mvc.perform(get("/api/v1/history/continue-watching").with(jwt().jwt(j -> j.subject("u1")))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void historyIsPerUserAndErasable() throws Exception {
        UUID t = UUID.randomUUID();
        progress("alice", t, 40, 100);
        mvc.perform(get("/api/v1/history/continue-watching").with(jwt().jwt(j -> j.subject("alice")))).andExpect(jsonPath("$[0].positionSeconds").value(40));
        mvc.perform(get("/api/v1/history/continue-watching").with(jwt().jwt(j -> j.subject("bob")))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/v1/history").with(jwt().jwt(j -> j.subject("alice")))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/history").with(jwt().jwt(j -> j.subject("alice")))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidProgressRejected() throws Exception {
        mvc.perform(put("/api/v1/history/progress").with(jwt().jwt(j -> j.subject("u")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":\"" + UUID.randomUUID() + "\",\"positionSeconds\":-1,\"durationSeconds\":0}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/watchlist")).andExpect(status().isUnauthorized());
    }
}
