package ai.streamsphere.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Title(
        UUID id,
        String creatorId,
        String creatorName,
        String kind,
        String title,
        String synopsis,
        List<String> genres,
        String language,
        String maturityRating,
        Integer releaseYear,
        Integer durationSeconds,
        EditorialState editorialState,
        String mediaState,
        UUID mediaAssetId,
        Integer mediaVersion,
        boolean hasPoster,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        int version) {

    public boolean ownedBy(String userId) { return creatorId.equals(userId); }
}
