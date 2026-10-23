package ai.streamsphere.worker;

import java.util.List;
import java.util.UUID;

/** Worker's copy of the transcode contract (contracts/events/Transcode*.json). No shared domain library. */
public final class Messages {
    private Messages() {}

    public record TranscodeRequested(UUID jobId, UUID assetId, UUID titleId, int attempt, String sourceBucket, String sourceKey,
                                     String sourceSha256, String outputBucket, String outputPrefix, int maxHeight, int segmentSeconds) {}

    public record RenditionResult(String name, int width, int height, int videoKbps, int audioKbps, int bandwidth,
                                  String codecs, String playlistKey, int segmentCount, long bytes) {}

    public record TranscodeCompleted(UUID jobId, UUID assetId, String outputPrefix, int durationSeconds, int width, int height,
                                     boolean hasAudio, List<RenditionResult> renditions, String posterKey, int thumbnailCount,
                                     long totalBytes, double wallSeconds, double cpuSeconds, String ffmpegVersion) {}

    public record TranscodeFailed(UUID jobId, UUID assetId, String error, boolean retryable) {}

    public record TranscodeStarted(UUID jobId, UUID assetId) {}
}
