package ai.streamsphere.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    public record TitleRequest(
            @NotBlank @Size(max = 160) String title,
            @Size(max = 4000) String synopsis,
            @Size(max = 5) List<@Pattern(regexp = "[a-z][a-z-]{1,30}") String> genres,
            @Pattern(regexp = "[a-z]{2}(-[A-Z]{2})?") String language,
            @Pattern(regexp = "G|PG|PG-13|R|NR") String maturityRating,
            @Min(1888) @Max(2100) Integer releaseYear,
            @Pattern(regexp = "FILM|SERIES") String kind) {}

    /** Public projection — never exposes internal moderation notes. */
    public record TitleView(UUID id, String title, String synopsis, List<String> genres, String language,
                            String maturityRating, Integer releaseYear, Integer durationSeconds, String creatorId,
                            String creatorName, String posterPath, Instant publishedAt) {
        static TitleView of(Title t) {
            return new TitleView(t.id(), t.title(), t.synopsis(), t.genres(), t.language(), t.maturityRating(),
                    t.releaseYear(), t.durationSeconds(), t.creatorId(), t.creatorName(),
                    t.hasPoster() && t.mediaAssetId() != null ? "/api/v1/media/assets/" + t.mediaAssetId() + "/poster" : null,
                    t.publishedAt());
        }
    }

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    // ---- event payloads (contracts/events) ----
    public record ContentSubmitted(UUID titleId, String creatorId, String creatorName, String title, String synopsis,
                                   List<String> genres, UUID mediaAssetId, Integer mediaVersion) {}

    public record ContentPublished(UUID titleId, String creatorId, String creatorName, String title, String synopsis,
                                   List<String> genres, String language, String maturityRating, Integer releaseYear,
                                   Integer durationSeconds, UUID mediaAssetId, Integer mediaVersion, Instant publishedAt) {}

    public record ContentRemoved(UUID titleId, String creatorId, UUID mediaAssetId, String reason, String removedBy) {}
}
