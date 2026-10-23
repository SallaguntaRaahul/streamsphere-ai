package ai.streamsphere.playback;

import ai.streamsphere.common.messaging.OutboxWriter;
import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.storage.StorageProvider;
import ai.streamsphere.common.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/playback")
public class PlaybackController {
    public record StartRequest(@NotNull UUID titleId) {}
    public record CaptionRef(String language, String label, boolean isDefault) {}
    public record Session(UUID sessionId, UUID titleId, UUID assetId, String manifestUrl, Instant expiresAt, boolean preview,
                          List<CaptionRef> captions, Integer durationSeconds) {}
    public record PlaybackStarted(UUID sessionId, UUID titleId, UUID assetId, String creatorId, String userId, boolean preview) {}

    static final Pattern SEGMENT = Pattern.compile("seg_\\d{5}\\.ts");
    static final Pattern LANG = Pattern.compile("[a-z]{2}(-[A-Z]{2})?");
    static final MediaType M3U8 = MediaType.parseMediaType("application/vnd.apple.mpegurl");
    static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final JdbcClient jdbc;
    private final Upstreams upstreams;
    private final Revocations revocations;
    private final StorageProvider storage;
    private final PlaybackToken tokens;
    private final OutboxWriter outbox;
    private final Duration sessionTtl;
    private final Duration segmentUrlTtl;
    private final Timer authTimer;
    private final Map<String, AtomicLong> pendingCounts = new ConcurrentHashMap<>();
    private final Map<String, String> playlistCache = new ConcurrentHashMap<>();

    public PlaybackController(JdbcClient jdbc, Upstreams upstreams, Revocations revocations, StorageProvider storage,
                              OutboxWriter outbox, MeterRegistry meters,
                              @Value("${streamsphere.playback.token-secret}") String secret,
                              @Value("${streamsphere.playback.session-ttl:PT15M}") Duration sessionTtl,
                              @Value("${streamsphere.playback.segment-url-ttl:PT60S}") Duration segmentUrlTtl) {
        this.jdbc = jdbc;
        this.upstreams = upstreams;
        this.revocations = revocations;
        this.storage = storage;
        this.tokens = new PlaybackToken(secret);
        this.outbox = outbox;
        this.sessionTtl = sessionTtl;
        this.segmentUrlTtl = segmentUrlTtl;
        this.authTimer = Timer.builder("streamsphere_playback_authorization_seconds").publishPercentileHistogram().register(meters);
    }

    /** Authorizes playback: published & not taken down (or owner/staff preview), media READY and current. */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Session start(@Valid @RequestBody StartRequest r) {
        return authTimer.record(() -> {
            String user = CurrentUser.id();
            Upstreams.TitleInfo t = upstreams.title(r.titleId());
            boolean privileged = user.equals(t.creatorId()) || CurrentUser.isStaff();
            if (!"PUBLISHED".equals(t.editorialState()) && !(privileged && !"REMOVED".equals(t.editorialState()))) {
                throw ApiException.notFound("Title");
            }
            if (t.mediaAssetId() == null || !"READY".equals(t.mediaState())) throw ApiException.conflict("media-not-ready", "No playable media");
            Upstreams.Manifest m = upstreams.manifest(t.mediaAssetId());
            if (!"READY".equals(m.state()) || m.removed() || !m.titleId().equals(t.id())) throw ApiException.conflict("media-not-ready", "No playable media");
            UUID sid = UUID.randomUUID();
            if (revocations.isRevoked(sid, t.id())) throw new ApiException(HttpStatus.GONE, "content-removed", "This title is no longer available");
            boolean preview = !"PUBLISHED".equals(t.editorialState());
            Instant exp = Instant.now().plus(sessionTtl);
            jdbc.sql("""
                    insert into playback_sessions(id, user_id, title_id, asset_id, asset_version, preview, created_at, expires_at)
                    values (:id, :u, :t, :a, :v, :p, now(), :e)""")
                .param("id", sid).param("u", user).param("t", t.id()).param("a", m.assetId()).param("v", m.assetVersion())
                .param("p", preview).param("e", java.sql.Timestamp.from(exp)).update();
            if (!preview) outbox.append("PlaybackStarted", 1, t.id().toString(), new PlaybackStarted(sid, t.id(), m.assetId(), t.creatorId(), user, false));
            String token = tokens.issue(new PlaybackToken.Claims(sid, t.id(), m.assetId(), exp));
            return new Session(sid, t.id(), m.assetId(), "/api/v1/playback/stream/" + token + "/master.m3u8", exp, preview,
                    m.captions().stream().map(c -> new CaptionRef(c.language(), c.label(), c.isDefault())).toList(), m.durationSeconds());
        });
    }

    @PostMapping("/sessions/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable UUID id) {
        String owner = jdbc.sql("select user_id from playback_sessions where id = :id").param("id", id).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Session"));
        if (!owner.equals(CurrentUser.id()) && !CurrentUser.hasRole("admin")) throw ApiException.notFound("Session");
        revocations.revokeSession(id);
    }

    @PostMapping("/admin/titles/{titleId}/revoke")
    @PreAuthorize("hasAnyRole('admin','moderator')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeTitle(@PathVariable UUID titleId) { revocations.revokeTitle(titleId, "manual revoke by " + CurrentUser.id()); }

    // ------------------------------------------------------------------ token-authorized delivery (public paths)

    @GetMapping(value = "/stream/{token}/master.m3u8")
    public ResponseEntity<String> master(@PathVariable String token) {
        var c = authorize(token);
        Upstreams.Manifest m = upstreams.manifest(c.assetId());
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        boolean subs = !m.captions().isEmpty();
        for (Upstreams.Caption cap : m.captions()) {
            b.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"").append(cap.label().replace("\"", "'"))
             .append("\",LANGUAGE=\"").append(cap.language()).append("\",DEFAULT=").append(cap.isDefault() ? "YES" : "NO")
             .append(",AUTOSELECT=YES,URI=\"subs/").append(cap.language()).append(".m3u8\"\n");
        }
        for (Upstreams.Rendition r : m.renditions()) {
            b.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(r.bandwidth()).append(",RESOLUTION=").append(r.width()).append('x').append(r.height())
             .append(",CODECS=\"").append(r.codecs()).append('"').append(subs ? ",SUBTITLES=\"subs\"" : "").append('\n')
             .append("v/").append(r.name()).append("/index.m3u8\n");
        }
        count("playlist");
        return ResponseEntity.ok().contentType(M3U8).cacheControl(NO_STORE).body(b.toString());
    }

    @GetMapping("/stream/{token}/v/{rendition}/index.m3u8")
    public ResponseEntity<String> variant(@PathVariable String token, @PathVariable String rendition) {
        var c = authorize(token);
        Upstreams.Manifest m = upstreams.manifest(c.assetId());
        Upstreams.Rendition r = m.renditions().stream().filter(x -> x.name().equals(rendition)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Rendition"));
        String body = playlistCache.computeIfAbsent(r.playlistKey(), k -> {
            try (InputStream in = storage.open(m.bucket(), k)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "storage-error", "Could not read playlist");
            }
        });
        count("playlist");
        return ResponseEntity.ok().contentType(M3U8).cacheControl(NO_STORE).body(body);
    }

    @GetMapping("/stream/{token}/v/{rendition}/{segment}")
    public ResponseEntity<Void> segment(@PathVariable String token, @PathVariable String rendition, @PathVariable String segment) {
        var c = authorize(token);
        if (!SEGMENT.matcher(segment).matches()) throw ApiException.notFound("Segment");
        Upstreams.Manifest m = upstreams.manifest(c.assetId());
        if (m.renditions().stream().noneMatch(x -> x.name().equals(rendition))) throw ApiException.notFound("Rendition");
        URI url = URI.create(storage.presignGet(m.bucket(), m.outputPrefix() + rendition + "/" + segment, segmentUrlTtl).toString());
        count("segment");
        return ResponseEntity.status(HttpStatus.FOUND).location(url).cacheControl(NO_STORE).build();
    }

    @GetMapping("/stream/{token}/subs/{file}")
    public ResponseEntity<String> subtitles(@PathVariable String token, @PathVariable String file) {
        var c = authorize(token);
        Upstreams.Manifest m = upstreams.manifest(c.assetId());
        String lang = file.replaceAll("\\.(m3u8|vtt)$", "");
        if (!LANG.matcher(lang).matches()) throw ApiException.notFound("Subtitles");
        Upstreams.Caption cap = m.captions().stream().filter(x -> x.language().equals(lang)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Subtitles"));
        if (file.endsWith(".m3u8")) {
            int d = m.durationSeconds() == null ? 1 : Math.max(1, m.durationSeconds());
            String pl = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + d + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:"
                    + d + ".0,\n" + lang + ".vtt\n#EXT-X-ENDLIST\n";
            return ResponseEntity.ok().contentType(M3U8).cacheControl(NO_STORE).body(pl);
        }
        URI url = URI.create(storage.presignGet(m.bucket(), cap.objectKey(), segmentUrlTtl).toString());
        return ResponseEntity.status(HttpStatus.FOUND).location(url).cacheControl(NO_STORE).build();
    }

    @GetMapping("/admin/stats")
    @PreAuthorize("hasRole('admin')")
    public Map<String, Object> stats() {
        flushCounts();
        var counts = jdbc.sql("select kind, count from delivery_counters where month = :m").param("m", month())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list();
        long active = jdbc.sql("select count(*) from playback_sessions where expires_at > now() and revoked_at is null").query(Long.class).single();
        return Map.of("month", month(), "deliveryRequests", counts, "activeSessions", active,
                "note", "segment redirects ≈ R2 Class B GETs performed by browsers (estimate)");
    }

    private PlaybackToken.Claims authorize(String token) {
        PlaybackToken.Claims c;
        try {
            c = tokens.verify(token, Instant.now());
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "playback-token-invalid", "Playback authorization invalid or expired");
        }
        if (revocations.isRevoked(c.sessionId(), c.titleId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "playback-revoked", "Playback has been revoked");
        }
        return c;
    }

    private void count(String kind) { pendingCounts.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet(); }

    @Scheduled(fixedDelay = 10_000)
    public void flushCounts() {
        pendingCounts.forEach((kind, n) -> {
            long v = n.getAndSet(0);
            if (v > 0) {
                jdbc.sql("""
                        insert into delivery_counters(month, kind, count) values (:m, :k, :n)
                        on conflict (month, kind) do update set count = delivery_counters.count + excluded.count""")
                    .param("m", month()).param("k", kind).param("n", v).update();
            }
        });
    }

    static String month() { return YearMonth.now(ZoneOffset.UTC).toString(); }
}
