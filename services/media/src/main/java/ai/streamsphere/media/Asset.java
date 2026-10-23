package ai.streamsphere.media;

import java.time.Instant;
import java.util.UUID;

public record Asset(UUID id, UUID titleId, String creatorId, UUID uploadId, int assetVersion, String sourceBucket,
                    String sourceKey, String sourceSha256, long sourceBytes, String state, String provider,
                    String providerVersion, String delivery, String outputBucket, String outputPrefix,
                    Integer durationSeconds, Integer width, Integer height, Boolean hasAudio, Long totalBytes,
                    boolean hasPoster, int thumbnailCount, boolean published, boolean removed, String error,
                    Instant createdAt, Instant updatedAt, Instant readyAt) {
    static final String COLS = """
            id, title_id, creator_id, upload_id, asset_version, source_bucket, source_key, source_sha256, source_bytes, state,
            provider, provider_version, delivery, output_bucket, output_prefix, duration_seconds, width, height, has_audio,
            total_bytes, has_poster, thumbnail_count, published, removed, error, created_at, updated_at, ready_at""";
}
