package ai.streamsphere.creator;

import ai.streamsphere.common.messaging.OutboxWriter;
import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CreatorController {
    /** Bump when the legal text of the attestation changes; stored with each attestation. */
    public static final String ATTESTATION_TEXT_VERSION = "2026-10-v1";

    public record Creator(String userId, String displayName, String bio, String country, String status, Instant createdAt, int version) {}

    public record PublicCreator(String userId, String displayName, String bio) {}

    public record RegisterRequest(@NotBlank @Size(max = 80) String displayName, @Size(max = 2000) String bio,
                                  @Pattern(regexp = "[A-Z]{2}") String country,
                                  @AssertTrue(message = "You must accept the creator terms") boolean acceptTerms) {}

    public record Attestation(UUID id, String creatorId, UUID titleId, String licenseType, String evidenceUrl,
                              String statementVersion, Instant attestedAt) {}

    public record AttestRequest(@NotNull UUID titleId,
                                @NotNull @Pattern(regexp = "ORIGINAL|LICENSED|PUBLIC_DOMAIN|CC_BY|CC_BY_SA|CC0") String licenseType,
                                @Size(max = 500) @Pattern(regexp = "https://.*") String evidenceUrl,
                                @AssertTrue(message = "You must confirm you hold all rights needed to distribute this work") boolean confirmRights) {}

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public CreatorController(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @PostMapping("/creators")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Creator register(@Valid @RequestBody RegisterRequest r) {
        String me = CurrentUser.id();
        int n = jdbc.sql("""
                insert into creators(user_id, display_name, bio, country, status, terms_version, created_at, version)
                values (:u, :n, :b, :c, 'ACTIVE', :tv, now(), 0) on conflict (user_id) do nothing""")
            .param("u", me).param("n", r.displayName().strip()).param("b", r.bio()).param("c", r.country())
            .param("tv", ATTESTATION_TEXT_VERSION).update();
        if (n == 0) throw ApiException.conflict("already-registered", "You are already registered as a creator");
        outbox.append("CreatorRegistered", 1, me, java.util.Map.of("creatorId", me, "displayName", r.displayName().strip()));
        return find(me);
    }

    @GetMapping("/creators/me")
    public Creator me() { return find(CurrentUser.id()); }

    @PutMapping("/creators/me")
    public Creator updateMe(@Valid @RequestBody RegisterRequest r) {
        int n = jdbc.sql("update creators set display_name = :n, bio = :b, country = :c, version = version + 1 where user_id = :u")
                .param("n", r.displayName().strip()).param("b", r.bio()).param("c", r.country()).param("u", CurrentUser.id()).update();
        if (n == 0) throw ApiException.notFound("Creator");
        return find(CurrentUser.id());
    }

    @GetMapping("/creators/{userId}")
    public PublicCreator publicProfile(@PathVariable String userId) {
        Creator c = find(userId);
        return new PublicCreator(c.userId(), c.displayName(), c.bio());
    }

    @PostMapping("/creators/me/attestations")
    @ResponseStatus(HttpStatus.CREATED)
    public Attestation attest(@Valid @RequestBody AttestRequest r) {
        String me = CurrentUser.id();
        Creator c = find(me);
        if (!"ACTIVE".equals(c.status())) throw ApiException.forbidden("Creator account is not active");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into rights_attestations(id, creator_id, title_id, license_type, evidence_url, statement_version, attested_at)
                values (:id, :c, :t, :l, :e, :v, now())""")
            .param("id", id).param("c", me).param("t", r.titleId()).param("l", r.licenseType())
            .param("e", r.evidenceUrl()).param("v", ATTESTATION_TEXT_VERSION).update();
        return attestations(me, r.titleId()).getFirst();
    }

    @GetMapping("/creators/me/attestations")
    public List<Attestation> myAttestations(@RequestParam(required = false) UUID titleId) {
        return attestations(CurrentUser.id(), titleId);
    }

    /** Internal: moderation reviews the rights claim with a service token. */
    @GetMapping("/internal/creators/{userId}/attestations")
    @PreAuthorize("hasRole('service')")
    public List<Attestation> internalAttestations(@PathVariable String userId, @RequestParam(required = false) UUID titleId) {
        return attestations(userId, titleId);
    }

    @PostMapping("/internal/creators/{userId}/suspend")
    @PreAuthorize("hasRole('admin')")
    public Creator suspend(@PathVariable String userId) {
        jdbc.sql("update creators set status = 'SUSPENDED', version = version + 1 where user_id = :u").param("u", userId).update();
        return find(userId);
    }

    private List<Attestation> attestations(String creatorId, UUID titleId) {
        return jdbc.sql("""
                select id, creator_id, title_id, license_type, evidence_url, statement_version, attested_at
                from rights_attestations where creator_id = :c and (cast(:t as uuid) is null or title_id = :t)
                order by attested_at desc""")
            .param("c", creatorId).param("t", titleId).query(Attestation.class).list();
    }

    private Creator find(String userId) {
        return jdbc.sql("select user_id, display_name, bio, country, status, created_at, version from creators where user_id = :u")
                .param("u", userId).query(Creator.class).optional().orElseThrow(() -> ApiException.notFound("Creator"));
    }
}
