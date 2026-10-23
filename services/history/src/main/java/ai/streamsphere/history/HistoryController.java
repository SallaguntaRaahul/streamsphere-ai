package ai.streamsphere.history;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import ai.streamsphere.common.messaging.OutboxWriter;
import ai.streamsphere.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class HistoryController {
    public record ProgressUpdate(@NotNull UUID titleId, @Min(0) @Max(36_000) int positionSeconds, @Min(1) @Max(36_000) int durationSeconds) {}
    public record Progress(UUID titleId, int positionSeconds, int durationSeconds, boolean completed, Instant updatedAt) {}
    public record WatchlistItem(UUID titleId, Instant addedAt) {}
    record Progressed(String userId, UUID titleId, int positionSeconds, int durationSeconds, int milestonePercent) {}
    record Completed(String userId, UUID titleId, int durationSeconds) {}

    static final double COMPLETE_AT = 0.90;
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public HistoryController(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /**
     * Idempotent upsert from the player (sent every ~10 s and on pause/exit). Events are emitted only when a
     * 25 % milestone is crossed for the first time, and once on completion (≥ 90 %), so analytics volume stays bounded.
     */
    @PutMapping("/history/progress")
    @Transactional
    public Progress update(@Valid @RequestBody ProgressUpdate u) {
        String me = CurrentUser.id();
        int pos = Math.min(u.positionSeconds(), u.durationSeconds());
        var prev = jdbc.sql("select last_milestone, completed from watch_progress where user_id = :u and title_id = :t for update")
                .param("u", me).param("t", u.titleId()).query((rs, i) -> new int[]{rs.getInt(1), rs.getBoolean(2) ? 1 : 0}).optional();
        int lastMilestone = prev.map(p -> p[0]).orElse(0);
        boolean wasCompleted = prev.map(p -> p[1] == 1).orElse(false);
        int milestone = (int) (pos * 4L / u.durationSeconds()) * 25;
        boolean completed = pos >= u.durationSeconds() * COMPLETE_AT;
        jdbc.sql("""
                insert into watch_progress(user_id, title_id, position_seconds, duration_seconds, last_milestone, completed, updated_at)
                values (:u, :t, :p, :d, :m, :c, now())
                on conflict (user_id, title_id) do update set position_seconds = excluded.position_seconds,
                  duration_seconds = excluded.duration_seconds, last_milestone = greatest(watch_progress.last_milestone, excluded.last_milestone),
                  completed = excluded.completed, updated_at = now()""")
            .param("u", me).param("t", u.titleId()).param("p", completed ? 0 : pos).param("d", u.durationSeconds())
            .param("m", Math.max(milestone, lastMilestone)).param("c", completed).update();
        if (milestone > lastMilestone && milestone < 100) {
            outbox.append("PlaybackProgressed", 1, u.titleId().toString(), new Progressed(me, u.titleId(), pos, u.durationSeconds(), milestone));
        }
        if (completed && !wasCompleted) outbox.append("PlaybackCompleted", 1, u.titleId().toString(), new Completed(me, u.titleId(), u.durationSeconds()));
        return get(u.titleId());
    }

    @GetMapping("/history/progress/{titleId}")
    public Progress get(@PathVariable UUID titleId) {
        return jdbc.sql("select title_id, position_seconds, duration_seconds, completed, updated_at from watch_progress where user_id = :u and title_id = :t")
                .param("u", CurrentUser.id()).param("t", titleId).query(Progress.class).optional()
                .orElse(new Progress(titleId, 0, 1, false, null));
    }

    @GetMapping("/history/continue-watching")
    public List<Progress> continueWatching() {
        return jdbc.sql("""
                select title_id, position_seconds, duration_seconds, completed, updated_at from watch_progress w
                where user_id = :u and not completed and position_seconds >= 5
                  and not exists (select 1 from removed_titles r where r.title_id = w.title_id)
                order by updated_at desc limit 20""").param("u", CurrentUser.id()).query(Progress.class).list();
    }

    @GetMapping("/history")
    public List<Progress> all() {
        return jdbc.sql("select title_id, position_seconds, duration_seconds, completed, updated_at from watch_progress where user_id = :u order by updated_at desc limit 200")
                .param("u", CurrentUser.id()).query(Progress.class).list();
    }

    /** Privacy: viewers can erase their watch history. */
    @DeleteMapping("/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void erase() { jdbc.sql("delete from watch_progress where user_id = :u").param("u", CurrentUser.id()).update(); }

    @GetMapping("/watchlist")
    public List<WatchlistItem> watchlist() {
        return jdbc.sql("""
                select title_id, added_at from watchlists w where user_id = :u
                  and not exists (select 1 from removed_titles r where r.title_id = w.title_id) order by added_at desc limit 500""")
            .param("u", CurrentUser.id()).query(WatchlistItem.class).list();
    }

    @PutMapping("/watchlist/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@PathVariable UUID titleId) {
        jdbc.sql("insert into watchlists(user_id, title_id, added_at) values (:u, :t, now()) on conflict do nothing")
                .param("u", CurrentUser.id()).param("t", titleId).update();
    }

    @DeleteMapping("/watchlist/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID titleId) {
        jdbc.sql("delete from watchlists where user_id = :u and title_id = :t").param("u", CurrentUser.id()).param("t", titleId).update();
    }

    @Component
    static class Events implements EventHandler {
        private final JdbcClient jdbc;
        Events(JdbcClient jdbc) { this.jdbc = jdbc; }
        @Override public Set<String> routingKeys() { return Set.of("ContentRemoved.v1"); }
        @Override public void handle(EventEnvelope e) {
            jdbc.sql("insert into removed_titles(title_id) values (:t) on conflict do nothing")
                    .param("t", UUID.fromString(e.payload().get("titleId").asString())).update();
        }
    }
}
