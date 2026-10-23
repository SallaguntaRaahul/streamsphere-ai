package ai.streamsphere.catalog;

import ai.streamsphere.catalog.Dtos.ContentPublished;
import ai.streamsphere.catalog.Dtos.ContentRemoved;
import ai.streamsphere.catalog.Dtos.ContentSubmitted;
import ai.streamsphere.catalog.Dtos.TitleRequest;
import ai.streamsphere.common.cache.SafeCache;
import ai.streamsphere.common.messaging.OutboxWriter;
import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.web.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TitleService {
    private final TitleRepository repo;
    private final OutboxWriter outbox;
    private final CreatorClient creators;
    private final Optional<SafeCache> cache;

    public TitleService(TitleRepository repo, OutboxWriter outbox, CreatorClient creators, Optional<SafeCache> cache) {
        this.repo = repo;
        this.outbox = outbox;
        this.creators = creators;
        this.cache = cache;
    }

    @Transactional
    public Title createDraft(TitleRequest r) {
        var me = creators.requireRegisteredCreator();
        String name = String.valueOf(me.getOrDefault("displayName", CurrentUser.displayName()));
        Title t = new Title(UUID.randomUUID(), CurrentUser.id(), name, r.kind() == null ? "FILM" : r.kind(), r.title().strip(),
                r.synopsis(), r.genres() == null ? List.of() : r.genres(), r.language() == null ? "en" : r.language(),
                r.maturityRating() == null ? "NR" : r.maturityRating(), r.releaseYear(), null, EditorialState.DRAFT,
                "NONE", null, null, false, null, null, null, null, 0);
        repo.insert(t);
        repo.audit(t.id(), t.creatorId(), "CREATED", null);
        return repo.find(t.id()).orElseThrow();
    }

    @Transactional
    public Title update(UUID id, TitleRequest r, int expectedVersion) {
        Title cur = ownedForUpdate(id);
        if (!cur.editorialState().editable()) throw ApiException.conflict("not-editable", "Title can only be edited in DRAFT or REJECTED");
        Title next = new Title(cur.id(), cur.creatorId(), cur.creatorName(), cur.kind(), r.title().strip(), r.synopsis(),
                r.genres() == null ? cur.genres() : r.genres(), r.language() == null ? cur.language() : r.language(),
                r.maturityRating() == null ? cur.maturityRating() : r.maturityRating(),
                r.releaseYear() == null ? cur.releaseYear() : r.releaseYear(), cur.durationSeconds(), cur.editorialState(),
                cur.mediaState(), cur.mediaAssetId(), cur.mediaVersion(), cur.hasPoster(), cur.rejectionReason(),
                cur.createdAt(), cur.updatedAt(), cur.publishedAt(), cur.version());
        repo.updateMetadata(next, expectedVersion);
        if (cur.editorialState() == EditorialState.REJECTED) repo.setEditorialState(id, EditorialState.DRAFT, cur.rejectionReason());
        return repo.find(id).orElseThrow();
    }

    @Transactional
    public Title submitForReview(UUID id) {
        Title t = ownedForUpdate(id);
        if (!"READY".equals(t.mediaState())) throw ApiException.conflict("media-not-ready", "Upload and processing must finish before review");
        if (!creators.hasRightsAttestation(id.toString())) throw ApiException.conflict("rights-attestation-required", "Attest that you hold the rights to this title");
        transition(t, EditorialState.IN_REVIEW, null);
        outbox.append("ContentSubmitted", 1, id.toString(), new ContentSubmitted(id, t.creatorId(), t.creatorName(), t.title(),
                t.synopsis(), t.genres(), t.mediaAssetId(), t.mediaVersion()));
        return repo.find(id).orElseThrow();
    }

    @Transactional
    public Title publish(UUID id) {
        Title t = ownedForUpdate(id);
        if (!"READY".equals(t.mediaState())) throw ApiException.conflict("media-not-ready", "Media is not ready");
        transition(t, EditorialState.PUBLISHED, null);
        Title p = repo.find(id).orElseThrow();
        outbox.append("ContentPublished", 1, id.toString(), new ContentPublished(id, p.creatorId(), p.creatorName(), p.title(),
                p.synopsis(), p.genres(), p.language(), p.maturityRating(), p.releaseYear(), p.durationSeconds(),
                p.mediaAssetId(), p.mediaVersion(), p.publishedAt()));
        evictPublicCache();
        return p;
    }

    /** Creator-initiated deletion: content leaves the catalog and every downstream service is told to purge. */
    @Transactional
    public void requestDeletion(UUID id) {
        Title t = ownedForUpdate(id);
        if (t.editorialState() == EditorialState.REMOVED) return;
        transition(t, EditorialState.REMOVED, "creator-deleted");
        outbox.append("ContentRemoved", 1, id.toString(),
                new ContentRemoved(id, t.creatorId(), t.mediaAssetId(), "creator-deleted", t.creatorId()));
        evictPublicCache();
    }

    // ---- event-driven transitions (called from handlers; actor = system) ----

    @Transactional
    public void onModerationDecision(UUID id, boolean approved, String reason, boolean appeal) {
        repo.findForUpdate(id).ifPresent(t -> {
            boolean pending = t.editorialState() == EditorialState.IN_REVIEW
                    || (appeal && approved && t.editorialState() == EditorialState.REJECTED);
            if (!pending) return; // stale/duplicate decision
            transition(t, approved ? EditorialState.APPROVED : EditorialState.REJECTED, approved ? null : reason);
        });
    }

    @Transactional
    public void onTakedown(UUID id, String reason) {
        repo.findForUpdate(id).ifPresent(t -> {
            if (t.editorialState() == EditorialState.REMOVED) return;
            repo.setEditorialState(id, EditorialState.REMOVED, reason);
            repo.audit(id, "moderation", "REMOVED", reason);
            evictPublicCache();
        });
    }

    @Transactional
    public void onMedia(UUID titleId, String state, UUID assetId, Integer version, Integer duration, Boolean poster) {
        repo.findForUpdate(titleId).ifPresent(t -> {
            // never let an older asset version overwrite a newer one
            if (t.mediaVersion() != null && version != null && version < t.mediaVersion()) return;
            if (t.editorialState() == EditorialState.PUBLISHED || t.editorialState() == EditorialState.REMOVED) return;
            repo.setMedia(titleId, state, assetId, version, duration, poster);
        });
    }

    public Title visibleTo(UUID id) {
        Title t = repo.find(id).orElseThrow(() -> ApiException.notFound("Title"));
        if (t.editorialState() == EditorialState.PUBLISHED) return t;
        Optional<String> me = CurrentUser.idIfPresent();
        if (me.isPresent() && (t.ownedBy(me.get()) || CurrentUser.isStaff())) return t;
        throw ApiException.notFound("Title"); // 404 not 403: do not reveal existence
    }

    private Title ownedForUpdate(UUID id) {
        Title t = repo.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Title"));
        if (!t.ownedBy(CurrentUser.id())) throw ApiException.notFound("Title");
        return t;
    }

    private void transition(Title t, EditorialState next, String reason) {
        if (!t.editorialState().canMoveTo(next)) {
            throw new ApiException(HttpStatus.CONFLICT, "illegal-transition", t.editorialState() + " -> " + next + " is not allowed");
        }
        repo.setEditorialState(t.id(), next, reason);
        repo.audit(t.id(), CurrentUser.idIfPresent().orElse("system"), next.name(), reason);
    }

    void evictPublicCache() { cache.ifPresent(c -> c.evictPrefix("catalog:public:")); }
}
