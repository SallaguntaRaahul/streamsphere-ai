package ai.streamsphere.catalog;

import java.util.Map;
import java.util.Set;

/** Editorial lifecycle, independent from media processing state. */
public enum EditorialState {
    DRAFT, IN_REVIEW, APPROVED, REJECTED, PUBLISHED, REMOVED;

    private static final Map<EditorialState, Set<EditorialState>> ALLOWED = Map.of(
            DRAFT, Set.of(IN_REVIEW, REMOVED),
            IN_REVIEW, Set.of(APPROVED, REJECTED, REMOVED),
            REJECTED, Set.of(DRAFT, IN_REVIEW, APPROVED, REMOVED), // APPROVED via upheld appeal
            APPROVED, Set.of(PUBLISHED, REMOVED),
            PUBLISHED, Set.of(REMOVED),
            REMOVED, Set.of());

    public boolean canMoveTo(EditorialState next) { return ALLOWED.get(this).contains(next); }

    public boolean editable() { return this == DRAFT || this == REJECTED; }
}
