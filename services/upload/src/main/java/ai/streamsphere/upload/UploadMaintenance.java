package ai.streamsphere.upload;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import ai.streamsphere.common.storage.StorageProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciler + event projections: expires abandoned multipart uploads, deletes sources after
 * transcoding (retention window) or takedown, and tracks rendition bytes for the cost guard.
 */
@Component
public class UploadMaintenance implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(UploadMaintenance.class);
    private final JdbcClient jdbc;
    private final StorageProvider storage;
    private final UploadProperties props;

    public UploadMaintenance(JdbcClient jdbc, StorageProvider storage, UploadProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.props = props;
    }

    @Override
    public Set<String> routingKeys() { return Set.of("MediaReady.v1", "ContentRemoved.v1", "MediaAssetsDeleted.v1"); }

    @Override
    public void handle(EventEnvelope e) {
        var p = e.payload();
        switch (e.eventType()) {
            case "MediaReady" -> {
                jdbc.sql("insert into media_storage(asset_id, title_id, bytes) values (:a, :t, :b) on conflict (asset_id) do update set bytes = excluded.bytes")
                        .param("a", UUID.fromString(p.get("assetId").asString())).param("t", UUID.fromString(p.get("titleId").asString()))
                        .param("b", p.path("totalBytes").asLong(0)).update();
                jdbc.sql("update upload_sessions set source_delete_after = :t where id = :u and source_delete_after is null")
                        .param("t", Timestamp.from(Instant.now().plus(props.sourceRetentionAfterReady())))
                        .param("u", UUID.fromString(p.get("uploadId").asString())).update();
            }
            case "ContentRemoved" -> jdbc.sql("update upload_sessions set source_delete_after = now() where title_id = :t and source_deleted_at is null")
                    .param("t", UUID.fromString(p.get("titleId").asString())).update();
            case "MediaAssetsDeleted" -> jdbc.sql("delete from media_storage where title_id = :t")
                    .param("t", UUID.fromString(p.get("titleId").asString())).update();
            default -> { }
        }
    }

    record Expired(UUID id, String bucket, String objectKey, String s3UploadId) {}

    @Scheduled(fixedDelayString = "${streamsphere.upload.reconcile-ms:60000}", initialDelay = 15000)
    public void reconcile() {
        List<Expired> expired = jdbc.sql("""
                select id, bucket, object_key, s3_upload_id from upload_sessions
                where state in ('INITIATED','UPLOADING') and expires_at < now() limit 100""").query(Expired.class).list();
        for (Expired x : expired) {
            try {
                storage.abortMultipartUpload(x.bucket(), x.objectKey(), x.s3UploadId());
                jdbc.sql("update upload_sessions set state = 'EXPIRED', source_deleted_at = now() where id = :id").param("id", x.id()).update();
                log.info("Expired abandoned upload {}", x.id());
            } catch (RuntimeException ex) {
                log.warn("Could not expire upload {}: {}", x.id(), ex.toString());
            }
        }
        List<Expired> due = jdbc.sql("""
                select id, bucket, object_key, s3_upload_id from upload_sessions
                where source_delete_after < now() and source_deleted_at is null limit 100""").query(Expired.class).list();
        for (Expired x : due) {
            try {
                storage.delete(x.bucket(), x.objectKey());
                jdbc.sql("update upload_sessions set source_deleted_at = now() where id = :id").param("id", x.id()).update();
            } catch (RuntimeException ex) {
                log.warn("Could not delete source for upload {}: {}", x.id(), ex.toString());
            }
        }
    }
}
