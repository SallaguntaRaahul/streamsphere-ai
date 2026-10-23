package ai.streamsphere.analytics;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import ai.streamsphere.common.security.CurrentUser;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class Analytics {
    public record QoeBeacon(@NotNull UUID sessionId, @NotNull UUID titleId, @Min(0) @Max(120_000) Integer startupMs,
                            @Min(0) @Max(3_600_000) Integer rebufferMs, @Min(0) @Max(3_600_000) Integer playedMs,
                            @Min(0) @Max(100_000) Integer bitrateKbps, @Min(0) @Max(1000) Integer bitrateSwitches,
                            @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9_.-]*") String errorCode) {}
    public record QoeBatch(@NotEmpty @Size(max = 50) List<@Valid QoeBeacon> beacons) {}
    public record Daily(LocalDate day, int views, int completions, long watchSeconds) {}
    public record TitleStats(UUID titleId, String title, int views, String uniqueViewers, int completions, double completionRate,
                             long watchSeconds, Integer avgStartupMs, Double rebufferRatio, List<Daily> daily) {}

    /** Below this, unique-viewer counts are suppressed (k-anonymity for small audiences). */
    static final int K_ANON = 5;
    private final JdbcClient jdbc;
    private final String salt;
    private final DistributionSummary startup;

    public Analytics(JdbcClient jdbc, @Value("${streamsphere.analytics.user-hash-salt}") String salt, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.salt = salt;
        this.startup = DistributionSummary.builder("streamsphere_hls_startup_ms").publishPercentiles(0.5, 0.95).register(meters);
    }

    @PostMapping("/qoe")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Integer> qoe(@Valid @RequestBody QoeBatch b) {
        String uh = hash(salt, CurrentUser.id());
        for (QoeBeacon q : b.beacons()) {
            jdbc.sql("""
                    insert into playback_events(id, kind, session_id, title_id, user_hash, startup_ms, rebuffer_ms, played_ms, bitrate_kbps,
                      bitrate_switches, error_code, occurred_at) values (:id, 'QOE', :s, :t, :u, :st, :rb, :pl, :br, :sw, :err, now())""")
                .param("id", UUID.randomUUID()).param("s", q.sessionId()).param("t", q.titleId()).param("u", uh)
                .param("st", q.startupMs()).param("rb", q.rebufferMs()).param("pl", q.playedMs()).param("br", q.bitrateKbps())
                .param("sw", q.bitrateSwitches()).param("err", q.errorCode()).update();
            if (q.startupMs() != null) startup.record(q.startupMs());
        }
        return Map.of("accepted", b.beacons().size());
    }

    @GetMapping("/creator/me/titles")
    public List<TitleStats> creatorStats(@RequestParam(defaultValue = "30") int days) {
        int d = Math.max(1, Math.min(days, 365));
        String me = CurrentUser.id();
        List<Map.Entry<UUID, String>> titles = jdbc.sql("select title_id, title from title_owners where creator_id = :c order by title")
                .param("c", me).query((rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2))).list();
        List<TitleStats> out = new ArrayList<>();
        for (var t : titles) {
            List<Daily> daily = jdbc.sql("""
                    select day, views, completions, watch_seconds from creator_daily_stats
                    where title_id = :t and creator_id = :c and day >= current_date - :d order by day""")
                .param("t", t.getKey()).param("c", me).param("d", d).query(Daily.class).list();
            var agg = jdbc.sql("""
                    select count(*) filter (where kind = 'STARTED'), count(distinct user_hash) filter (where kind = 'STARTED'),
                           count(*) filter (where kind = 'COMPLETED'), coalesce(sum(watched_seconds), 0),
                           avg(startup_ms) filter (where kind = 'QOE' and startup_ms is not null),
                           sum(rebuffer_ms) filter (where kind = 'QOE'), sum(played_ms) filter (where kind = 'QOE')
                    from playback_events where title_id = :t and occurred_at >= now() - make_interval(days => :d)""")
                .param("t", t.getKey()).param("d", d).query((rs, i) -> new Object[]{rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4),
                        rs.getObject(5), rs.getObject(6), rs.getObject(7)}).single();
            int views = (int) agg[0], uniques = (int) agg[1], completions = (int) agg[2];
            Number rb = (Number) agg[5], pl = (Number) agg[6];
            Double ratio = rb != null && pl != null && (rb.doubleValue() + pl.doubleValue()) > 0 ? rb.doubleValue() / (rb.doubleValue() + pl.doubleValue()) : null;
            out.add(new TitleStats(t.getKey(), t.getValue(), views, uniques < K_ANON ? "<" + K_ANON : String.valueOf(uniques), completions,
                    views == 0 ? 0 : Math.round(1000.0 * completions / views) / 1000.0, (long) agg[3],
                    agg[4] == null ? null : ((Number) agg[4]).intValue(), ratio == null ? null : Math.round(ratio * 10000) / 10000.0, daily));
        }
        return out;
    }

    @GetMapping("/admin/qoe")
    @PreAuthorize("hasRole('admin')")
    public Map<String, Object> platformQoe(@RequestParam(defaultValue = "7") int days) {
        return jdbc.sql("""
                select count(*) as beacons,
                       percentile_cont(0.5) within group (order by startup_ms) as p50,
                       percentile_cont(0.95) within group (order by startup_ms) as p95,
                       coalesce(sum(rebuffer_ms),0) as rb, coalesce(sum(played_ms),0) as pl,
                       count(*) filter (where error_code is not null and error_code <> '') as errors
                from playback_events where kind = 'QOE' and occurred_at >= now() - make_interval(days => :d)""")
            .param("d", Math.max(1, Math.min(days, 90)))
            .query((rs, i) -> {
                double rbv = rs.getDouble("rb"), plv = rs.getDouble("pl");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("beacons", rs.getLong("beacons"));
                m.put("startupP50Ms", rs.getObject("p50"));
                m.put("startupP95Ms", rs.getObject("p95"));
                m.put("rebufferRatio", rbv + plv > 0 ? rbv / (rbv + plv) : null);
                m.put("errorBeacons", rs.getLong("errors"));
                m.put("note", "Measured from real client beacons only; targets in docs/architecture/RELIABILITY.md are not claims.");
                return m;
            }).single();
    }

    /** Recomputes today's and yesterday's daily stats (idempotent upsert). */
    @Scheduled(fixedDelayString = "${streamsphere.analytics.aggregate-ms:60000}", initialDelay = 10_000)
    public void aggregate() {
        jdbc.sql("""
                insert into creator_daily_stats(title_id, day, creator_id, views, unique_viewers, completions, watch_seconds, avg_startup_ms, rebuffer_ratio, computed_at)
                select e.title_id, e.occurred_at::date, o.creator_id,
                       count(*) filter (where kind = 'STARTED'), count(distinct user_hash) filter (where kind = 'STARTED'),
                       count(*) filter (where kind = 'COMPLETED'), coalesce(sum(watched_seconds), 0),
                       avg(startup_ms) filter (where kind = 'QOE')::int,
                       case when sum(played_ms) filter (where kind = 'QOE') > 0
                            then sum(rebuffer_ms) filter (where kind = 'QOE')::float / nullif(sum(rebuffer_ms) filter (where kind = 'QOE') + sum(played_ms) filter (where kind = 'QOE'), 0) end,
                       now()
                from playback_events e join title_owners o on o.title_id = e.title_id
                where e.occurred_at >= current_date - 1
                group by e.title_id, e.occurred_at::date, o.creator_id
                on conflict (title_id, day) do update set views = excluded.views, unique_viewers = excluded.unique_viewers,
                  completions = excluded.completions, watch_seconds = excluded.watch_seconds, avg_startup_ms = excluded.avg_startup_ms,
                  rebuffer_ratio = excluded.rebuffer_ratio, computed_at = now()""").update();
    }

    /** Retention: raw events older than 180 days are deleted (aggregates are kept). */
    @Scheduled(cron = "0 17 3 * * *")
    public void retention() { jdbc.sql("delete from playback_events where occurred_at < now() - interval '180 days'").update(); }

    static String hash(String salt, String userId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Optional append-only stream for playback events (Kafka in local-full). */
    public interface StreamSink { void append(EventEnvelope e); }

    @Component
    static class Events implements EventHandler {
        private final JdbcClient jdbc;
        private final String salt;
        private final java.util.Optional<StreamSink> sink;

        Events(JdbcClient jdbc, @Value("${streamsphere.analytics.user-hash-salt}") String salt, java.util.Optional<StreamSink> sink) {
            this.jdbc = jdbc;
            this.salt = salt;
            this.sink = sink;
        }

        @Override
        public Set<String> routingKeys() {
            return Set.of("ContentPublished.v1", "PlaybackStarted.v1", "PlaybackProgressed.v1", "PlaybackCompleted.v1");
        }

        @Override
        public void handle(EventEnvelope e) {
            apply(e);
            if (e.eventType().startsWith("Playback")) sink.ifPresent(s -> s.append(e));
        }

        /** Idempotent projection update (event id is the row key) — used by live consumption and Kafka replay. */
        void apply(EventEnvelope e) {
            var p = e.payload();
            UUID t = UUID.fromString(p.get("titleId").asString());
            switch (e.eventType()) {
                case "ContentPublished" -> jdbc.sql("""
                        insert into title_owners(title_id, creator_id, title, duration_seconds) values (:t, :c, :ti, :d)
                        on conflict (title_id) do update set title = excluded.title, duration_seconds = excluded.duration_seconds""")
                    .param("t", t).param("c", p.get("creatorId").asString()).param("ti", p.get("title").asString())
                    .param("d", p.hasNonNull("durationSeconds") ? p.get("durationSeconds").asInt() : null).update();
                case "PlaybackStarted" -> insert(e, "STARTED", t, UUID.fromString(p.get("sessionId").asString()), p.path("userId").asString(), null);
                case "PlaybackProgressed" -> insert(e, "PROGRESS", t, null, p.path("userId").asString(),
                        // each 25 % milestone ≈ a quarter of the duration watched
                        p.path("durationSeconds").asInt(0) / 4);
                case "PlaybackCompleted" -> insert(e, "COMPLETED", t, null, p.path("userId").asString(), p.path("durationSeconds").asInt(0) / 4);
                default -> { }
            }
        }

        private void insert(EventEnvelope e, String kind, UUID title, UUID session, String user, Integer watched) {
            jdbc.sql("""
                    insert into playback_events(id, kind, session_id, title_id, user_hash, watched_seconds, occurred_at)
                    values (:id, :k, :s, :t, :u, :w, :at) on conflict (id) do nothing""")
                .param("id", e.eventId()).param("k", kind).param("s", session).param("t", title).param("u", hash(salt, user))
                .param("w", watched).param("at", java.sql.Timestamp.from(e.occurredAt() == null ? Instant.now() : e.occurredAt())).update();
        }
    }
}
