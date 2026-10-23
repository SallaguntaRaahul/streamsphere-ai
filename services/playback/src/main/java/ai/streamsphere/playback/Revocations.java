package ai.streamsphere.playback;

import ai.streamsphere.common.cache.SafeCache;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Revocation lookups on every playlist/segment request. Valkey sets are only trusted when the
 * "loaded" marker is present (protects against a restarted, empty cache); any cache error falls back
 * to PostgreSQL, the source of truth.
 */
@Component
public class Revocations {
    static final String TITLES = "playback:revoked:titles";
    static final String SESSIONS = "playback:revoked:sessions";
    static final String LOADED = "playback:revoked:loaded";
    private final JdbcClient jdbc;
    private final SafeCache cache;

    public Revocations(JdbcClient jdbc, SafeCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    public boolean isRevoked(UUID sessionId, UUID titleId) {
        if (cache.get(LOADED).isEmpty()) rebuild();
        Optional<Boolean> t = cache.isMember(TITLES, titleId.toString());
        Optional<Boolean> s = cache.isMember(SESSIONS, sessionId.toString());
        if (t.isPresent() && s.isPresent() && cache.get(LOADED).isPresent()) return t.get() || s.get();
        return fromDb(sessionId, titleId);
    }

    boolean fromDb(UUID sessionId, UUID titleId) {
        return jdbc.sql("""
                select exists(select 1 from revoked_titles where title_id = :t)
                    or exists(select 1 from playback_sessions where id = :s and revoked_at is not null)""")
            .param("t", titleId).param("s", sessionId).query(Boolean.class).single();
    }

    public void revokeTitle(UUID titleId, String reason) {
        jdbc.sql("insert into revoked_titles(title_id, reason, revoked_at) values (:t, :r, now()) on conflict do nothing")
                .param("t", titleId).param("r", reason).update();
        jdbc.sql("update playback_sessions set revoked_at = now() where title_id = :t and revoked_at is null").param("t", titleId).update();
        cache.addMember(TITLES, titleId.toString());
    }

    public void revokeSession(UUID sessionId) {
        jdbc.sql("update playback_sessions set revoked_at = now() where id = :s and revoked_at is null").param("s", sessionId).update();
        cache.addMember(SESSIONS, sessionId.toString());
    }

    /** Bounds staleness if a cache write was lost while Valkey was briefly unreachable. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void resync() {
        jdbc.sql("select title_id from revoked_titles").query(UUID.class).list().forEach(t -> cache.addMember(TITLES, t.toString()));
        jdbc.sql("select id from playback_sessions where revoked_at is not null and expires_at > now()").query(UUID.class).list()
                .forEach(s -> cache.addMember(SESSIONS, s.toString()));
    }

    synchronized void rebuild() {
        if (cache.get(LOADED).isPresent()) return;
        jdbc.sql("select title_id from revoked_titles").query(UUID.class).list().forEach(t -> cache.addMember(TITLES, t.toString()));
        jdbc.sql("select id from playback_sessions where revoked_at is not null and expires_at > now()").query(UUID.class).list()
                .forEach(s -> cache.addMember(SESSIONS, s.toString()));
        cache.put(LOADED, "1", Duration.ofDays(3650));
    }
}
