package ai.streamsphere.media;

import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.storage.StorageProvider;
import ai.streamsphere.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MediaController {
    public record Rendition(String name, int width, int height, int videoKbps, int audioKbps, int bandwidth, String codecs,
                            String playlistKey, int segmentCount, long bytes) {}
    public record Caption(UUID id, String language, String label, String objectKey, boolean isDefault, Instant createdAt) {}
    public record Job(UUID id, int attempt, String launcher, String state, Instant queuedAt, Instant startedAt, Instant finishedAt,
                      Double wallSeconds, Double cpuSeconds, String error) {}
    public record AssetDetail(Asset asset, List<Rendition> renditions, List<Caption> captions, List<Job> jobs) {}
    public record Manifest(UUID assetId, UUID titleId, String creatorId, int assetVersion, String state, boolean published,
                           boolean removed, String bucket, String outputPrefix, int segmentSeconds, Integer durationSeconds,
                           List<Rendition> renditions, List<Caption> captions) {}
    public record CaptionUpload(@NotBlank @Pattern(regexp = "[a-z]{2}(-[A-Z]{2})?") String language,
                                @NotBlank @Size(max = 40) String label, boolean isDefault,
                                @NotBlank @Size(max = 524288) String webvtt) {}

    private static final java.util.regex.Pattern CUE = java.util.regex.Pattern.compile("(\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3} --> (\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}");

    private final JdbcClient jdbc;
    private final StorageProvider storage;
    private final MediaPipeline pipeline;
    private final String bucket;

    public MediaController(JdbcClient jdbc, StorageProvider storage, MediaPipeline pipeline,
                           @Value("${streamsphere.media.output-bucket}") String bucket) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.pipeline = pipeline;
        this.bucket = bucket;
    }

    @GetMapping("/media/titles/{titleId}/assets")
    public List<AssetDetail> forTitle(@PathVariable UUID titleId) {
        List<Asset> assets = jdbc.sql("select " + Asset.COLS + " from media_assets where title_id = :t order by asset_version desc")
                .param("t", titleId).query(Asset.class).list();
        return assets.stream().filter(this::canManage).map(this::detail).toList();
    }

    @GetMapping("/media/assets/{id}")
    public AssetDetail get(@PathVariable UUID id) {
        Asset a = find(id);
        if (!canManage(a)) throw ApiException.notFound("Asset");
        return detail(a);
    }

    /** Posters are public only for published content; owners/staff can always see theirs. */
    @GetMapping("/media/assets/{id}/poster")
    public ResponseEntity<Void> poster(@PathVariable UUID id) {
        Asset a = find(id);
        if (!a.hasPoster() || a.outputPrefix() == null || a.removed()) throw ApiException.notFound("Poster");
        if (!a.published() && !canManage(a)) throw ApiException.notFound("Poster");
        URI url = URI.create(storage.presignGet(bucket, a.outputPrefix() + "poster.jpg", Duration.ofMinutes(60)).toString());
        return ResponseEntity.status(HttpStatus.FOUND).location(url)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate()).build();
    }

    @PostMapping("/media/assets/{id}/captions")
    @ResponseStatus(HttpStatus.CREATED)
    public Caption addCaption(@PathVariable UUID id, @Valid @RequestBody CaptionUpload r) {
        Asset a = find(id);
        if (!a.creatorId().equals(CurrentUser.id())) throw ApiException.notFound("Asset");
        String vtt = r.webvtt().replace("\r\n", "\n");
        if (!vtt.startsWith("WEBVTT")) throw ApiException.badRequest("invalid-webvtt", "File must start with WEBVTT");
        if (!CUE.matcher(vtt).find()) throw ApiException.badRequest("invalid-webvtt", "No cues found");
        if (vtt.toLowerCase().contains("<script") || vtt.contains("javascript:")) throw ApiException.badRequest("invalid-webvtt", "Markup not allowed");
        UUID cid = UUID.randomUUID();
        String key = "assets/" + a.id() + "/captions/" + r.language() + "-" + cid + ".vtt";
        storage.put(bucket, key, vtt.getBytes(StandardCharsets.UTF_8), "text/vtt; charset=utf-8");
        if (r.isDefault()) jdbc.sql("update caption_tracks set is_default = false where asset_id = :a").param("a", id).update();
        jdbc.sql("""
                insert into caption_tracks(id, asset_id, language, label, object_key, is_default, created_at)
                values (:id, :a, :l, :lb, :k, :d, now())
                on conflict (asset_id, language) do update set label = excluded.label, object_key = excluded.object_key,
                  is_default = excluded.is_default, created_at = now()""")
            .param("id", cid).param("a", id).param("l", r.language()).param("lb", r.label()).param("k", key).param("d", r.isDefault()).update();
        return captions(id).stream().filter(c -> c.language().equals(r.language())).findFirst().orElseThrow();
    }

    @DeleteMapping("/media/assets/{id}/captions/{language}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCaption(@PathVariable UUID id, @PathVariable String language) {
        Asset a = find(id);
        if (!a.creatorId().equals(CurrentUser.id())) throw ApiException.notFound("Asset");
        jdbc.sql("delete from caption_tracks where asset_id = :a and language = :l").param("a", id).param("l", language).update();
    }

    /** Internal: playback builds playlists from this manifest. */
    @GetMapping("/internal/assets/{id}/manifest")
    @PreAuthorize("hasRole('service')")
    public Manifest manifest(@PathVariable UUID id) {
        Asset a = find(id);
        return new Manifest(a.id(), a.titleId(), a.creatorId(), a.assetVersion(), a.state(), a.published(), a.removed(),
                a.outputBucket(), a.outputPrefix(), 6, a.durationSeconds(), renditions(id), captions(id));
    }

    @GetMapping("/media/admin/jobs")
    @PreAuthorize("hasRole('admin')")
    public Map<String, Object> jobStats() {
        var byState = jdbc.sql("select state, count(*) from processing_jobs group by state")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list();
        var perf = jdbc.sql("""
                select count(*) as n, coalesce(avg(wall_seconds),0) as avg_wall, coalesce(sum(cpu_seconds),0) as cpu
                from processing_jobs where state = 'SUCCEEDED'""").query((rs, i) -> Map.of("succeeded", rs.getLong(1),
                "avgWallSeconds", rs.getDouble(2), "totalCpuSeconds", rs.getDouble(3))).single();
        return Map.of("byState", byState, "performance", perf);
    }

    private boolean canManage(Asset a) {
        return CurrentUser.idIfPresent().map(u -> u.equals(a.creatorId())).orElse(false) || CurrentUser.isStaff();
    }

    private Asset find(UUID id) {
        return jdbc.sql("select " + Asset.COLS + " from media_assets where id = :id").param("id", id).query(Asset.class).optional()
                .orElseThrow(() -> ApiException.notFound("Asset"));
    }

    private AssetDetail detail(Asset a) {
        List<Job> jobs = jdbc.sql("""
                select id, attempt, launcher, state, queued_at, started_at, finished_at, wall_seconds, cpu_seconds, error
                from processing_jobs where asset_id = :a order by attempt""").param("a", a.id()).query(Job.class).list();
        return new AssetDetail(a, renditions(a.id()), captions(a.id()), jobs);
    }

    private List<Rendition> renditions(UUID id) {
        return jdbc.sql("""
                select name, width, height, video_kbps, audio_kbps, bandwidth, codecs, playlist_key, segment_count, bytes
                from renditions where asset_id = :a order by height""").param("a", id).query(Rendition.class).list();
    }

    private List<Caption> captions(UUID id) {
        return jdbc.sql("select id, language, label, object_key, is_default, created_at from caption_tracks where asset_id = :a order by language")
                .param("a", id).query(Caption.class).list();
    }
}
