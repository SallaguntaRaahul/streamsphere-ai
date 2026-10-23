package ai.streamsphere.moderation;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import ai.streamsphere.common.messaging.OutboxWriter;
import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.security.ServiceTokenClient;
import ai.streamsphere.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/v1/moderation")
public class ModerationController {
    public record Case(UUID id, UUID titleId, String creatorId, String creatorName, String title, String synopsis, UUID mediaAssetId,
                       Integer mediaVersion, String kind, String state, String reason, String decidedBy, Instant decidedAt,
                       Instant createdAt, int version) {}
    public record CaseDetail(Case reviewCase, List<Map<String, Object>> rightsAttestations, List<Map<String, Object>> reports,
                             List<Map<String, Object>> audit) {}
    public record Decision(@NotNull @Pattern(regexp = "APPROVE|REJECT|DISMISS") String decision, @Size(max = 1000) String reason) {}
    public record Report(@NotNull UUID titleId, @NotNull @Pattern(regexp = "COPYRIGHT|ILLEGAL|HARASSMENT|SEXUAL|VIOLENCE|SPAM|OTHER") String category,
                         @Size(max = 2000) String details) {}
    public record Takedown(@NotNull UUID titleId, @NotBlank @Size(max = 1000) String reason) {}
    public record Appeal(@NotNull UUID titleId, @NotBlank @Size(max = 2000) String statement) {}
    record Decided(UUID titleId, String creatorId, String title, String reason, boolean appeal, UUID caseId) {}
    record Removed(UUID titleId, String creatorId, UUID mediaAssetId, String reason, String removedBy) {}

    static final String COLS = "id, title_id, creator_id, creator_name, title, synopsis, media_asset_id, media_version, kind, state, reason, decided_by, decided_at, created_at, version";
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final RestClient creator;
    private final ServiceTokenClient tokens;

    public ModerationController(JdbcClient jdbc, OutboxWriter outbox, RestClient.Builder b, ServiceTokenClient tokens,
                                @Value("${streamsphere.services.creator}") String creatorUrl) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.creator = b.baseUrl(creatorUrl).build();
        this.tokens = tokens;
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('moderator','admin')")
    public List<Case> queue(@RequestParam(defaultValue = "OPEN") String state, @RequestParam(required = false) String kind) {
        return jdbc.sql("select " + COLS + " from review_cases where state = :s and (cast(:k as text) is null or kind = :k) order by created_at limit 200")
                .param("s", state).param("k", kind).query(Case.class).list();
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasAnyRole('moderator','admin')")
    public CaseDetail detail(@PathVariable UUID id) {
        Case c = find(id);
        List<Map<String, Object>> attest;
        try {
            attest = creator.get().uri("/api/v1/internal/creators/{u}/attestations?titleId={t}", c.creatorId(), c.titleId())
                    .header("Authorization", "Bearer " + tokens.token()).retrieve().body(List.class);
        } catch (RestClientException e) {
            attest = List.of(Map.of("error", "creator service unavailable"));
        }
        var reports = jdbc.sql("select category, details, created_at from content_reports where title_id = :t order by created_at desc limit 50")
                .param("t", c.titleId()).query((rs, i) -> Map.<String, Object>of("category", rs.getString(1),
                        "details", String.valueOf(rs.getString(2)), "createdAt", rs.getTimestamp(3).toInstant())).list();
        return new CaseDetail(c, attest, reports, audit(c.titleId()));
    }

    @PostMapping("/cases/{id}/decision")
    @PreAuthorize("hasAnyRole('moderator','admin')")
    @Transactional
    public Case decide(@PathVariable UUID id, @Valid @RequestBody Decision d) {
        Case c = jdbc.sql("select " + COLS + " from review_cases where id = :id for update").param("id", id).query(Case.class).optional()
                .orElseThrow(() -> ApiException.notFound("Case"));
        if (!c.state().equals("OPEN")) throw ApiException.conflict("case-closed", "Case already decided");
        if (c.creatorId().equals(CurrentUser.id())) throw ApiException.forbidden("You cannot moderate your own content");
        if (d.decision().equals("REJECT") && (d.reason() == null || d.reason().isBlank())) throw ApiException.badRequest("reason-required", "A reason is required to reject");
        String state = switch (d.decision()) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; default -> "DISMISSED"; };
        jdbc.sql("update review_cases set state = :s, reason = :r, decided_by = :u, decided_at = now(), version = version + 1 where id = :id")
                .param("s", state).param("r", d.reason()).param("u", CurrentUser.id()).param("id", id).update();
        audit(id, c.titleId(), d.decision(), d.reason());
        boolean appeal = c.kind().equals("APPEAL");
        if (c.kind().equals("SUBMISSION") || appeal) {
            if (state.equals("APPROVED")) outbox.append("ContentApproved", 1, c.titleId().toString(), new Decided(c.titleId(), c.creatorId(), c.title(), d.reason(), appeal, id));
            if (state.equals("REJECTED")) outbox.append("ContentRejected", 1, c.titleId().toString(), new Decided(c.titleId(), c.creatorId(), c.title(), d.reason(), appeal, id));
        }
        return find(id);
    }

    @PostMapping("/takedowns")
    @PreAuthorize("hasAnyRole('moderator','admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Map<String, Object> takedown(@Valid @RequestBody Takedown t) {
        var proj = jdbc.sql("select creator_id from title_projection where title_id = :t").param("t", t.titleId()).query(String.class).optional();
        String creatorId = proj.orElseGet(() -> jdbc.sql("select creator_id from review_cases where title_id = :t limit 1").param("t", t.titleId())
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Title")));
        jdbc.sql("update title_projection set removed = true, published = false where title_id = :t").param("t", t.titleId()).update();
        jdbc.sql("update review_cases set state = 'ACTIONED', decided_by = :u, decided_at = now(), reason = :r where title_id = :t and state = 'OPEN'")
                .param("u", CurrentUser.id()).param("r", t.reason()).param("t", t.titleId()).update();
        audit(null, t.titleId(), "TAKEDOWN", t.reason());
        outbox.append("ContentRemoved", 1, t.titleId().toString(), new Removed(t.titleId(), creatorId, null, "takedown: " + t.reason(), CurrentUser.id()));
        return Map.of("titleId", t.titleId(), "status", "REMOVAL_REQUESTED");
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Map<String, Object> report(@Valid @RequestBody Report r) {
        var t = jdbc.sql("select title_id, creator_id, title, creator_name from title_projection where title_id = :t and published")
                .param("t", r.titleId()).query((rs, i) -> new String[]{rs.getString(2), rs.getString(3), rs.getString(4)}).optional()
                .orElseThrow(() -> ApiException.notFound("Title"));
        UUID caseId = jdbc.sql("select id from review_cases where title_id = :t and kind = 'REPORT' and state = 'OPEN'").param("t", r.titleId())
                .query(UUID.class).optional().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.sql("""
                            insert into review_cases(id, title_id, creator_id, creator_name, title, kind, state, created_at)
                            values (:id, :t, :c, :cn, :ti, 'REPORT', 'OPEN', now())""")
                        .param("id", id).param("t", r.titleId()).param("c", t[0]).param("cn", t[2]).param("ti", t[1]).update();
                    return id;
                });
        jdbc.sql("""
                insert into content_reports(id, title_id, reporter_id, category, details, case_id, created_at)
                values (:id, :t, :u, :c, :d, :cid, now()) on conflict do nothing""")
            .param("id", UUID.randomUUID()).param("t", r.titleId()).param("u", CurrentUser.id()).param("c", r.category())
            .param("d", r.details()).param("cid", caseId).update();
        return Map.of("caseId", caseId, "status", "RECEIVED");
    }

    @PostMapping("/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Case appeal(@Valid @RequestBody Appeal a) {
        Case last = jdbc.sql("select " + COLS + " from review_cases where title_id = :t and kind in ('SUBMISSION','APPEAL') order by created_at desc limit 1")
                .param("t", a.titleId()).query(Case.class).optional().orElseThrow(() -> ApiException.notFound("Title"));
        if (!last.creatorId().equals(CurrentUser.id())) throw ApiException.notFound("Title");
        if (!last.state().equals("REJECTED")) throw ApiException.conflict("not-appealable", "Only rejected decisions can be appealed");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into review_cases(id, title_id, creator_id, creator_name, title, synopsis, media_asset_id, media_version, kind, state, reason, created_at)
                values (:id, :t, :c, :cn, :ti, :s, :a, :v, 'APPEAL', 'OPEN', :r, now())""")
            .param("id", id).param("t", a.titleId()).param("c", last.creatorId()).param("cn", last.creatorName()).param("ti", last.title())
            .param("s", last.synopsis()).param("a", last.mediaAssetId()).param("v", last.mediaVersion()).param("r", a.statement()).update();
        audit(id, a.titleId(), "APPEAL_FILED", a.statement());
        return find(id);
    }

    @GetMapping("/audit")
    @PreAuthorize("hasRole('admin')")
    public List<Map<String, Object>> auditLog(@RequestParam UUID titleId) { return audit(titleId); }

    private List<Map<String, Object>> audit(UUID titleId) {
        return jdbc.sql("select actor, action, detail, at from audit_events where title_id = :t order by at").param("t", titleId)
                .query((rs, i) -> Map.<String, Object>of("actor", rs.getString(1), "action", rs.getString(2),
                        "detail", String.valueOf(rs.getString(3)), "at", rs.getTimestamp(4).toInstant())).list();
    }

    private void audit(UUID caseId, UUID titleId, String action, String detail) {
        jdbc.sql("insert into audit_events(id, case_id, title_id, actor, action, detail, at) values (:id, :c, :t, :a, :ac, :d, now())")
                .param("id", UUID.randomUUID()).param("c", caseId).param("t", titleId)
                .param("a", CurrentUser.idIfPresent().orElse("system")).param("ac", action).param("d", detail).update();
    }

    private Case find(UUID id) {
        return jdbc.sql("select " + COLS + " from review_cases where id = :id").param("id", id).query(Case.class).optional()
                .orElseThrow(() -> ApiException.notFound("Case"));
    }

    /** Opens SUBMISSION cases and maintains the title projection used for reports/takedowns. */
    @Component
    static class Events implements EventHandler {
        private final JdbcClient jdbc;

        Events(JdbcClient jdbc) { this.jdbc = jdbc; }

        @Override public Set<String> routingKeys() { return Set.of("ContentSubmitted.v1", "ContentPublished.v1", "ContentRemoved.v1"); }

        @Override
        public void handle(EventEnvelope e) {
            var p = e.payload();
            UUID t = UUID.fromString(p.get("titleId").asString());
            switch (e.eventType()) {
                case "ContentSubmitted" -> {
                    upsertProjection(t, p, false);
                    jdbc.sql("""
                            insert into review_cases(id, title_id, creator_id, creator_name, title, synopsis, media_asset_id, media_version, kind, state, created_at)
                            values (:id, :t, :c, :cn, :ti, :s, :a, :v, 'SUBMISSION', 'OPEN', now()) on conflict do nothing""")
                        .param("id", UUID.randomUUID()).param("t", t).param("c", p.get("creatorId").asString())
                        .param("cn", p.path("creatorName").asString(null)).param("ti", p.get("title").asString())
                        .param("s", p.path("synopsis").asString(null))
                        .param("a", p.hasNonNull("mediaAssetId") ? UUID.fromString(p.get("mediaAssetId").asString()) : null)
                        .param("v", p.hasNonNull("mediaVersion") ? p.get("mediaVersion").asInt() : null).update();
                }
                case "ContentPublished" -> upsertProjection(t, p, true);
                case "ContentRemoved" -> jdbc.sql("update title_projection set removed = true, published = false where title_id = :t").param("t", t).update();
                default -> { }
            }
        }

        private void upsertProjection(UUID t, tools.jackson.databind.JsonNode p, boolean published) {
            jdbc.sql("""
                    insert into title_projection(title_id, creator_id, title, creator_name, published) values (:t, :c, :ti, :cn, :p)
                    on conflict (title_id) do update set title = excluded.title, published = excluded.published or title_projection.published""")
                .param("t", t).param("c", p.get("creatorId").asString()).param("ti", p.get("title").asString())
                .param("cn", p.path("creatorName").asString(null)).param("p", published).update();
        }
    }
}
