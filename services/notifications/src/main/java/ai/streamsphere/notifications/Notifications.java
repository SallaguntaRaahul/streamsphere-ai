package ai.streamsphere.notifications;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.web.CursorCodec;
import ai.streamsphere.common.web.PageResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class Notifications {
    public record Notification(UUID id, String kind, String title, String body, String link, Instant createdAt, Instant readAt) {}

    private final JdbcClient jdbc;

    public Notifications(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public PageResponse<Notification> list(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        int lim = Math.max(1, Math.min(limit, 50));
        var c = CursorCodec.decode(cursor);
        var rows = jdbc.sql("""
                select id, kind, title, body, link, created_at, read_at from notifications
                where user_id = :u and (cast(:at as timestamptz) is null or (created_at, id) < (:at, :id))
                order by created_at desc, id desc limit :l""")
            .param("u", CurrentUser.id()).param("at", c == null ? null : Timestamp.from(c.at())).param("id", c == null ? null : c.id())
            .param("l", lim + 1).query(Notification.class).list();
        boolean more = rows.size() > lim;
        var page = more ? rows.subList(0, lim) : rows;
        return new PageResponse<>(page, more ? CursorCodec.encode(page.getLast().createdAt(), page.getLast().id()) : null);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("unread", jdbc.sql("select count(*) from notifications where user_id = :u and read_at is null")
                .param("u", CurrentUser.id()).query(Long.class).single());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        jdbc.sql("update notifications set read_at = now() where id = :id and user_id = :u and read_at is null")
                .param("id", id).param("u", CurrentUser.id()).update();
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        jdbc.sql("update notifications set read_at = now() where user_id = :u and read_at is null").param("u", CurrentUser.id()).update();
    }

    /** Optional email channel. Disabled by default (no paid provider); attempts are recorded as SKIPPED. */
    public interface EmailChannel { void send(String userId, String subject, String body); }

    @Component
    static class Events implements EventHandler {
        private final JdbcClient jdbc;
        private final boolean emailEnabled;

        Events(JdbcClient jdbc, @Value("${streamsphere.notifications.email-enabled:false}") boolean emailEnabled) {
            this.jdbc = jdbc;
            this.emailEnabled = emailEnabled;
        }

        @Override
        public Set<String> routingKeys() {
            return Set.of("MediaReady.v1", "MediaProcessingFailed.v1", "ContentApproved.v1", "ContentRejected.v1", "ContentRemoved.v1", "ContentPublished.v1");
        }

        @Override
        public void handle(EventEnvelope e) {
            var p = e.payload();
            String user = p.path("creatorId").asString(null);
            if (user == null) return;
            String t = p.path("titleId").asString();
            String name = p.path("title").asString("your title");
            String link = "/creator/titles/" + t;
            switch (e.eventType()) {
                case "MediaReady" -> add(e, user, "MEDIA_READY", "Video processed", "Your upload finished processing and is ready to review.", link);
                case "MediaProcessingFailed" -> add(e, user, "MEDIA_FAILED", "Processing failed", "We could not process your upload: " + p.path("error").asString(""), link);
                case "ContentApproved" -> add(e, user, "APPROVED", "Approved: " + name, "Your title passed review. You can publish it now.", link);
                case "ContentRejected" -> add(e, user, "REJECTED", "Changes needed: " + name, "Reason: " + p.path("reason").asString("not specified") + ". You may edit and resubmit or appeal.", link);
                case "ContentRemoved" -> add(e, user, "REMOVED", "Title removed", "Reason: " + p.path("reason").asString(""), link);
                case "ContentPublished" -> add(e, user, "PUBLISHED", "Live: " + name, "Your title is now streaming on StreamSphere.", "/title/" + t);
                default -> { }
            }
        }

        private void add(EventEnvelope e, String user, String kind, String title, String body, String link) {
            UUID id = UUID.randomUUID();
            int n = jdbc.sql("""
                    insert into notifications(id, user_id, kind, title, body, link, source_event, created_at)
                    values (:id, :u, :k, :t, :b, :l, :e, now()) on conflict (source_event) do nothing""")
                .param("id", id).param("u", user).param("k", kind).param("t", title).param("b", body).param("l", link).param("e", e.eventId()).update();
            if (n == 0) return;
            jdbc.sql("insert into delivery_attempts(id, notification_id, channel, status, attempted_at) values (:id, :n, 'in-app', 'DELIVERED', now())")
                    .param("id", UUID.randomUUID()).param("n", id).update();
            jdbc.sql("insert into delivery_attempts(id, notification_id, channel, status, error, attempted_at) values (:id, :n, 'email', :s, :err, now())")
                    .param("id", UUID.randomUUID()).param("n", id).param("s", emailEnabled ? "FAILED" : "SKIPPED")
                    .param("err", emailEnabled ? "no email provider configured" : "email channel disabled").update();
        }
    }
}
