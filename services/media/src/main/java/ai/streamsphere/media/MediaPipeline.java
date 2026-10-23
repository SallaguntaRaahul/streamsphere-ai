package ai.streamsphere.media;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import ai.streamsphere.common.messaging.OutboxWriter;
import ai.streamsphere.common.storage.StorageProvider;
import ai.streamsphere.media.Contracts.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processing state machine: QUEUED → PROCESSING → READY | FAILED (DELETED on takedown).
 * Every attempt writes to its own immutable prefix; only a validated, fully-uploaded attempt
 * (worker writes _COMPLETE last, checked here) is ever referenced as the asset output.
 */
@Component
public class MediaPipeline implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(MediaPipeline.class);
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final TranscodeLauncher launcher;
    private final StorageProvider storage;
    private final JsonMapper json;
    private final String outputBucket;
    private final int maxHeight;
    private final int maxAttempts;
    private final String providerVersion;

    public MediaPipeline(JdbcClient jdbc, OutboxWriter outbox, TranscodeLauncher launcher, StorageProvider storage, JsonMapper json,
                         @Value("${streamsphere.media.output-bucket}") String outputBucket,
                         @Value("${streamsphere.media.max-height:720}") int maxHeight,
                         @Value("${streamsphere.media.max-attempts:3}") int maxAttempts,
                         @Value("${streamsphere.media.provider-version:ffmpeg-worker/1}") String providerVersion) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.launcher = launcher;
        this.storage = storage;
        this.json = json;
        this.outputBucket = outputBucket;
        this.maxHeight = maxHeight;
        this.maxAttempts = maxAttempts;
        this.providerVersion = providerVersion;
    }

    @Override
    public Set<String> routingKeys() {
        return Set.of("UploadCompleted.v1", "TranscodeStarted.v1", "TranscodeCompleted.v1", "TranscodeFailed.v1",
                "ContentPublished.v1", "ContentRemoved.v1");
    }

    @Override
    public void handle(EventEnvelope e) {
        var p = e.payload();
        switch (e.eventType()) {
            case "UploadCompleted" -> onUpload(p);
            case "TranscodeStarted" -> jdbc.sql("""
                    update processing_jobs set state = 'RUNNING', started_at = now() where id = :j and state = 'QUEUED'""")
                    .param("j", UUID.fromString(p.get("jobId").asString())).update();
            case "TranscodeCompleted" -> onCompleted(json.treeToValue(p, TranscodeCompleted.class));
            case "TranscodeFailed" -> onFailed(json.treeToValue(p, TranscodeFailed.class));
            case "ContentPublished" -> jdbc.sql("update media_assets set published = true, updated_at = now() where id = :a")
                    .param("a", UUID.fromString(p.get("mediaAssetId").asString())).update();
            case "ContentRemoved" -> onRemoved(UUID.fromString(p.get("titleId").asString()));
            default -> { }
        }
    }

    void onUpload(tools.jackson.databind.JsonNode p) {
        UUID uploadId = UUID.fromString(p.get("uploadId").asString());
        UUID titleId = UUID.fromString(p.get("titleId").asString());
        if (jdbc.sql("select count(*) from media_assets where upload_id = :u").param("u", uploadId).query(Integer.class).single() > 0) return;
        int version = jdbc.sql("select coalesce(max(asset_version), 0) + 1 from media_assets where title_id = :t")
                .param("t", titleId).query(Integer.class).single();
        UUID assetId = UUID.randomUUID();
        jdbc.sql("""
                insert into media_assets(id, title_id, creator_id, upload_id, asset_version, source_bucket, source_key, source_sha256,
                  source_bytes, state, provider, provider_version, output_bucket, created_at, updated_at)
                values (:id, :t, :c, :u, :v, :sb, :sk, :sha, :bytes, 'QUEUED', :prov, :pv, :ob, now(), now())""")
            .param("id", assetId).param("t", titleId).param("c", p.get("creatorId").asString()).param("u", uploadId)
            .param("v", version).param("sb", p.get("bucket").asString()).param("sk", p.get("objectKey").asString())
            .param("sha", p.get("sha256").asString()).param("bytes", p.get("sizeBytes").asLong())
            .param("prov", "ffmpeg-" + launcher.name()).param("pv", providerVersion).param("ob", outputBucket).update();
        startAttempt(assetId, titleId, version, 1, p.get("bucket").asString(), p.get("objectKey").asString(),
                p.get("sha256").asString(), p.get("sizeBytes").asLong());
    }

    private void startAttempt(UUID assetId, UUID titleId, int version, int attempt, String srcBucket, String srcKey, String sha, long bytes) {
        UUID jobId = UUID.randomUUID();
        String prefix = "assets/" + assetId + "/v" + version + "/a" + attempt + "/";
        // deadline scales with source size: 10 min + 1 min per 25 MB (≈ conservative for veryfast 3-rung on 2 vCPU)
        Duration deadline = Duration.ofMinutes(10 + bytes / 25_000_000L);
        jdbc.sql("""
                insert into processing_jobs(id, asset_id, attempt, launcher, state, output_prefix, queued_at, deadline_at)
                values (:id, :a, :n, :l, 'QUEUED', :p, now(), :d)""")
            .param("id", jobId).param("a", assetId).param("n", attempt).param("l", launcher.name()).param("p", prefix)
            .param("d", Timestamp.from(Instant.now().plus(deadline))).update();
        String ref = launcher.launch(new TranscodeRequested(jobId, assetId, titleId, attempt, srcBucket, srcKey, sha, outputBucket, prefix, maxHeight, 6));
        jdbc.sql("update processing_jobs set external_ref = :r where id = :id").param("r", ref).param("id", jobId).update();
        jdbc.sql("update media_assets set state = 'PROCESSING', updated_at = now() where id = :a").param("a", assetId).update();
        outbox.append("MediaProcessingStarted", 1, titleId.toString(), new MediaProcessingStarted(assetId, titleId, version, attempt));
    }

    void onCompleted(TranscodeCompleted c) {
        var job = jdbc.sql("select state, output_prefix from processing_jobs where id = :j for update").param("j", c.jobId())
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
        if (job.isEmpty() || job.get()[0].equals("SUCCEEDED")) return;               // unknown or duplicate
        if (!c.outputPrefix().equals(job.get()[1])) throw new IllegalStateException("output prefix mismatch for job " + c.jobId());
        if (storage.head(outputBucket, c.outputPrefix() + "_COMPLETE").isEmpty()) {   // never trust a partial output
            failAttempt(c.jobId(), c.assetId(), "output incomplete (no _COMPLETE marker)", true);
            return;
        }
        Asset a = asset(c.assetId());
        if (a.removed() || a.state().equals("READY")) return;
        jdbc.sql("""
                update processing_jobs set state = 'SUCCEEDED', finished_at = now(), wall_seconds = :w, cpu_seconds = :cpu where id = :j""")
            .param("w", c.wallSeconds()).param("cpu", c.cpuSeconds()).param("j", c.jobId()).update();
        for (RenditionResult r : c.renditions()) {
            jdbc.sql("""
                    insert into renditions(asset_id, name, width, height, video_kbps, audio_kbps, bandwidth, codecs, playlist_key, segment_count, bytes)
                    values (:a, :n, :w, :h, :vk, :ak, :bw, :c, :k, :sc, :b) on conflict do nothing""")
                .param("a", c.assetId()).param("n", r.name()).param("w", r.width()).param("h", r.height()).param("vk", r.videoKbps())
                .param("ak", r.audioKbps()).param("bw", r.bandwidth()).param("c", r.codecs()).param("k", r.playlistKey())
                .param("sc", r.segmentCount()).param("b", r.bytes()).update();
        }
        jdbc.sql("""
                update media_assets set state = 'READY', output_prefix = :p, duration_seconds = :d, width = :w, height = :h,
                  has_audio = :au, total_bytes = :tb, has_poster = :hp, thumbnail_count = :tc, ready_at = now(), updated_at = now(),
                  provider_version = :pv, error = null, version = version + 1 where id = :a""")
            .param("p", c.outputPrefix()).param("d", c.durationSeconds()).param("w", c.width()).param("h", c.height())
            .param("au", c.hasAudio()).param("tb", c.totalBytes()).param("hp", c.posterKey() != null).param("tc", c.thumbnailCount())
            .param("pv", providerVersion + " (" + c.ffmpegVersion() + ")").param("a", c.assetId()).update();
        outbox.append("MediaReady", 1, a.titleId().toString(), new MediaReady(a.id(), a.titleId(), a.uploadId(), a.creatorId(),
                a.assetVersion(), c.durationSeconds(), c.posterKey() != null, c.totalBytes(),
                c.renditions().stream().map(RenditionResult::name).toList()));
        log.info("Asset {} READY ({} renditions, {} s, wall {} s)", a.id(), c.renditions().size(), c.durationSeconds(), c.wallSeconds());
    }

    void onFailed(TranscodeFailed f) { failAttempt(f.jobId(), f.assetId(), f.error(), f.retryable()); }

    @Transactional
    void failAttempt(UUID jobId, UUID assetId, String error, boolean retryable) {
        int n = jdbc.sql("update processing_jobs set state = 'FAILED', finished_at = now(), error = :e where id = :j and state in ('QUEUED','RUNNING')")
                .param("e", truncate(error)).param("j", jobId).update();
        if (n == 0) return;
        Asset a = asset(assetId);
        String prefix = jdbc.sql("select output_prefix from processing_jobs where id = :j").param("j", jobId).query(String.class).single();
        cleanupQuietly(prefix);
        int attempts = jdbc.sql("select count(*) from processing_jobs where asset_id = :a").param("a", assetId).query(Integer.class).single();
        if (retryable && attempts < maxAttempts && !a.removed()) {
            log.warn("Transcode attempt {} for asset {} failed ({}); retrying", attempts, assetId, error);
            startAttempt(a.id(), a.titleId(), a.assetVersion(), attempts + 1, a.sourceBucket(), a.sourceKey(), a.sourceSha256(), a.sourceBytes());
        } else {
            jdbc.sql("update media_assets set state = 'FAILED', error = :e, updated_at = now() where id = :a")
                    .param("e", truncate(error)).param("a", assetId).update();
            outbox.append("MediaProcessingFailed", 1, a.titleId().toString(),
                    new MediaProcessingFailed(a.id(), a.titleId(), a.creatorId(), a.assetVersion(), truncate(error)));
        }
    }

    void onRemoved(UUID titleId) {
        List<Asset> assets = jdbc.sql("select " + Asset.COLS + " from media_assets where title_id = :t and not removed").param("t", titleId)
                .query(Asset.class).list();
        if (assets.isEmpty()) return;
        for (Asset a : assets) {
            jdbc.sql("update media_assets set removed = true, published = false, state = 'DELETED', updated_at = now() where id = :a")
                    .param("a", a.id()).update();
            cleanupQuietly("assets/" + a.id() + "/");
        }
        outbox.append("MediaAssetsDeleted", 1, titleId.toString(), new MediaAssetsDeleted(titleId, assets.stream().map(Asset::id).toList()));
    }

    /** Reconciler: jobs past their deadline (crashed worker, lost message, killed pod) are failed and retried. */
    @Scheduled(fixedDelayString = "${streamsphere.media.reconcile-ms:30000}", initialDelay = 20000)
    @Transactional
    public void reconcile() {
        List<UUID[]> overdue = jdbc.sql("""
                select id, asset_id from processing_jobs where state in ('QUEUED','RUNNING') and deadline_at < now()
                for update skip locked limit 20""").query((rs, i) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list();
        for (UUID[] j : overdue) {
            log.warn("Job {} exceeded its deadline; marking timed out", j[0]);
            failAttempt(j[0], j[1], "timed out (worker crash, lost message or deadline exceeded)", true);
        }
    }

    Asset asset(UUID id) {
        return jdbc.sql("select " + Asset.COLS + " from media_assets where id = :id").param("id", id).query(Asset.class).single();
    }

    private void cleanupQuietly(String prefix) {
        try {
            int n = storage.deletePrefix(outputBucket, prefix);
            if (n > 0) log.info("Deleted {} objects under {}", n, prefix);
        } catch (RuntimeException e) {
            log.warn("Cleanup of {} failed (reconciler will not retry automatically): {}", prefix, e.toString());
        }
    }

    private static String truncate(String s) { return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s; }
}
