package ai.streamsphere.upload;

import java.time.Instant;
import java.util.UUID;

public record UploadSession(UUID id, UUID titleId, String creatorId, String bucket, String objectKey, String s3UploadId,
                            String fileName, String contentType, long sizeBytes, long partSize, int partCount,
                            String sha256, String state, String error, String provider, Instant createdAt,
                            Instant completedAt, Instant expiresAt, int version) {
    public boolean terminal() { return state.equals("UPLOADED") || state.equals("CANCELLED") || state.equals("FAILED") || state.equals("EXPIRED"); }
}
