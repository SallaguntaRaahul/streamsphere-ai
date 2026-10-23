package ai.streamsphere.media;

import java.util.List;
import java.util.UUID;

/** Event payloads owned by media (see contracts/events). */
public final class Contracts {
    private Contracts() {}

    public record TranscodeRequested(UUID jobId, UUID assetId, UUID titleId, int attempt, String sourceBucket, String sourceKey,
                                     String sourceSha256, String outputBucket, String outputPrefix, int maxHeight,
                                     int segmentSeconds) {}

    public record RenditionResult(String name, int width, int height, int videoKbps, int audioKbps, int bandwidth,
                                  String codecs, String playlistKey, int segmentCount, long bytes) {}

    public record TranscodeCompleted(UUID jobId, UUID assetId, String outputPrefix, int durationSeconds, int width, int height,
                                     boolean hasAudio, List<RenditionResult> renditions, String posterKey, int thumbnailCount,
                                     long totalBytes, double wallSeconds, double cpuSeconds, String ffmpegVersion) {}

    public record TranscodeFailed(UUID jobId, UUID assetId, String error, boolean retryable) {}

    public record MediaProcessingStarted(UUID assetId, UUID titleId, int version, int attempt) {}

    public record MediaReady(UUID assetId, UUID titleId, UUID uploadId, String creatorId, int version, int durationSeconds,
                             boolean hasPoster, long totalBytes, List<String> renditions) {}

    public record MediaProcessingFailed(UUID assetId, UUID titleId, String creatorId, int version, String error) {}

    public record MediaAssetsDeleted(UUID titleId, List<UUID> assetIds) {}
}
