package ai.streamsphere.upload;

import ai.streamsphere.common.messaging.OutboxWriter;
import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.storage.StorageProvider;
import ai.streamsphere.common.storage.StorageProvider.Part;
import ai.streamsphere.common.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadService {
    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    static final String COLS = """
            id, title_id, creator_id, bucket, object_key, s3_upload_id, file_name, content_type, size_bytes, part_size,
            part_count, sha256, state, error, provider, created_at, completed_at, expires_at, version""";

    public record CreateRequest(UUID titleId, String fileName, String contentType, long sizeBytes, String sha256) {}
    public record PresignedPart(int partNumber, String url, Instant expiresAt) {}
    public record UploadCompleted(UUID uploadId, UUID titleId, String creatorId, String bucket, String objectKey,
                                  long sizeBytes, String contentType, String sha256, String provider) {}

    private final JdbcClient jdbc;
    private final StorageProvider storage;
    private final UploadProperties props;
    private final CostGuard cost;
    private final OwnershipClient ownership;
    private final OutboxWriter outbox;

    public UploadService(JdbcClient jdbc, StorageProvider storage, UploadProperties props, CostGuard cost,
                         OwnershipClient ownership, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.props = props;
        this.cost = cost;
        this.ownership = ownership;
        this.outbox = outbox;
    }

    public UploadSession create(CreateRequest r, String idempotencyKey) {
        String me = CurrentUser.id();
        if (idempotencyKey != null) {
            var existing = jdbc.sql("select " + COLS + " from upload_sessions where creator_id = :c and idempotency_key = :k")
                    .param("c", me).param("k", idempotencyKey).query(UploadSession.class).optional();
            if (existing.isPresent()) return existing.get();
        }
        admit(r.titleId(), r.contentType(), r.sizeBytes(), r.sha256());

        UUID id = UUID.randomUUID();
        String safeName = r.fileName() == null ? "source" : r.fileName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.length() > 100) safeName = safeName.substring(safeName.length() - 100);
        String key = "sources/" + me + "/" + r.titleId() + "/" + id + "/source";
        long partSize = Math.max(props.partSizeBytes(), (r.sizeBytes() + 9_999) / 10_000); // S3 max 10k parts, min 5 MiB
        int partCount = (int) ((r.sizeBytes() + partSize - 1) / partSize);
        String uploadId = storage.createMultipartUpload(props.bucket(), key, r.contentType());
        cost.countOps("classA", 1);
        jdbc.sql("""
                insert into upload_sessions(id, title_id, creator_id, bucket, object_key, s3_upload_id, file_name, content_type,
                  size_bytes, part_size, part_count, sha256, state, provider, idempotency_key, created_at, expires_at, version)
                values (:id, :t, :c, :b, :k, :u, :f, :ct, :s, :ps, :pc, :sha, 'INITIATED', :p, :ik, now(), :exp, 0)""")
            .param("id", id).param("t", r.titleId()).param("c", me).param("b", props.bucket()).param("k", key).param("u", uploadId)
            .param("f", safeName).param("ct", r.contentType()).param("s", r.sizeBytes()).param("ps", partSize).param("pc", partCount)
            .param("sha", r.sha256()).param("p", storage.providerName()).param("ik", idempotencyKey)
            .param("exp", java.sql.Timestamp.from(Instant.now().plus(props.sessionTtl())))
            .update();
        return get(id);
    }

    public UploadSession get(UUID id) {
        UploadSession s = jdbc.sql("select " + COLS + " from upload_sessions where id = :id").param("id", id)
                .query(UploadSession.class).optional().orElseThrow(() -> ApiException.notFound("Upload"));
        if (!s.creatorId().equals(CurrentUser.idIfPresent().orElse("")) && !CurrentUser.hasRole("admin")) throw ApiException.notFound("Upload");
        return s;
    }

    public PresignedPart presignPart(UUID id, int partNumber) {
        UploadSession s = get(id);
        if (s.terminal()) throw ApiException.conflict("upload-closed", "Upload is " + s.state());
        if (partNumber < 1 || partNumber > s.partCount()) throw ApiException.badRequest("invalid-part", "Part must be 1.." + s.partCount());
        if (s.expiresAt().isBefore(Instant.now())) throw ApiException.conflict("upload-expired", "Upload session expired");
        jdbc.sql("update upload_sessions set state = 'UPLOADING' where id = :id and state = 'INITIATED'").param("id", id).update();
        var url = storage.presignUploadPart(s.bucket(), s.objectKey(), s.s3UploadId(), partNumber, props.partUrlTtl());
        cost.countOps("classA", 1); // the UploadPart the browser will perform
        return new PresignedPart(partNumber, url.toString(), Instant.now().plus(props.partUrlTtl()));
    }

    /** Parts that actually exist in storage — the source of truth for resuming. */
    public List<Part> uploadedParts(UUID id) {
        UploadSession s = get(id);
        if (s.terminal()) return List.of();
        return storage.listParts(s.bucket(), s.objectKey(), s.s3UploadId());
    }

    /**
     * Completes and verifies: all parts present in storage with the client's ETags, final size equals the
     * declared size, and container signature is a supported video. Idempotent: a repeated call on an
     * UPLOADED session returns it unchanged without emitting a second event.
     */
    @Transactional
    public UploadSession complete(UUID id, List<Part> clientParts) {
        UploadSession s = jdbc.sql("select " + COLS + " from upload_sessions where id = :id for update").param("id", id)
                .query(UploadSession.class).optional().orElseThrow(() -> ApiException.notFound("Upload"));
        if (!s.creatorId().equals(CurrentUser.id())) throw ApiException.notFound("Upload");
        if (s.state().equals("UPLOADED")) return s;
        if (s.terminal()) throw ApiException.conflict("upload-closed", "Upload is " + s.state());

        List<Part> stored = storage.listParts(s.bucket(), s.objectKey(), s.s3UploadId());
        if (stored.size() != s.partCount()) {
            throw ApiException.conflict("parts-missing", "Storage has " + stored.size() + " of " + s.partCount() + " parts");
        }
        for (Part p : stored) {
            boolean match = clientParts.stream().anyMatch(c -> c.partNumber() == p.partNumber() && strip(c.etag()).equals(strip(p.etag())));
            if (!match) throw ApiException.conflict("etag-mismatch", "Part " + p.partNumber() + " does not match storage");
        }
        storage.completeMultipartUpload(s.bucket(), s.objectKey(), s.s3UploadId(), stored);
        cost.countOps("classA", 1);
        for (Part p : stored) {
            jdbc.sql("insert into upload_parts(session_id, part_number, etag, size_bytes) values (:s, :n, :e, :b) on conflict do nothing")
                    .param("s", id).param("n", p.partNumber()).param("e", strip(p.etag())).param("b", p.size()).update();
        }
        verifyAndPublish(s, s.objectKey());
        return jdbc.sql("select " + COLS + " from upload_sessions where id = :id").param("id", id).query(UploadSession.class).single();
    }

    /**
     * Shared by every upload protocol (S3 multipart, tus): the stored object — not the client — is the source of
     * truth. Checks size and container signature, then emits UploadCompleted.v1 via the outbox in the same tx.
     */
    void verifyAndPublish(UploadSession s, String objectKey) {
        UUID id = s.id();
        var meta = storage.head(s.bucket(), objectKey).orElseThrow(() -> new IllegalStateException("object missing after upload"));
        byte[] head = storage.readRange(s.bucket(), objectKey, 0, 4095);
        cost.countOps("classB", 2);
        String detected = MediaSignature.detect(head);
        String failure = null;
        if (meta.size() != s.sizeBytes()) failure = "size mismatch: declared " + s.sizeBytes() + ", stored " + meta.size();
        else if (detected == null) failure = "not a supported video container";
        if (failure != null) {
            log.warn("Upload {} rejected: {}", id, failure);
            storage.delete(s.bucket(), objectKey);
            jdbc.sql("update upload_sessions set state = 'FAILED', error = :e, source_deleted_at = now(), version = version + 1 where id = :id")
                    .param("e", failure).param("id", id).update();
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "verification-failed", failure);
        }
        jdbc.sql("update upload_sessions set state = 'UPLOADED', object_key = :k, content_type = :ct, completed_at = now(), version = version + 1 where id = :id")
                .param("k", objectKey).param("ct", detected).param("id", id).update();
        outbox.append("UploadCompleted", 1, s.titleId().toString(), new UploadCompleted(id, s.titleId(), s.creatorId(), s.bucket(),
                objectKey, meta.size(), detected, s.sha256(), s.provider()));
    }

    /** Admission checks shared by all protocols (size, type, checksum format, ownership, rights, cost guard). */
    void admit(UUID titleId, String contentType, long sizeBytes, String sha256) {
        if (sizeBytes <= 0 || sizeBytes > props.maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large", "File must be between 1 byte and " + props.maxBytes() + " bytes");
        }
        if (!props.allowedContentTypes().contains(contentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Allowed: " + props.allowedContentTypes());
        }
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw ApiException.badRequest("invalid-checksum", "sha256 (hex) is required");
        Map<String, Object> title = ownership.ownedTitle(titleId);
        String state = String.valueOf(title.get("editorialState"));
        if (!state.equals("DRAFT") && !state.equals("REJECTED")) throw ApiException.conflict("title-locked", "Uploads are only allowed while the title is a draft");
        if (!ownership.attested(titleId)) throw ApiException.conflict("rights-attestation-required", "Attest your rights to this title before uploading");
        cost.admit(sizeBytes);
    }

    /** tus pre-create: register a session keyed by the tus upload id (bytes go to storage via tusd, never through us). */
    public UploadSession registerTus(String tusId, UUID titleId, String fileName, String contentType, long sizeBytes, String sha256, String bucket) {
        admit(titleId, contentType, sizeBytes, sha256);
        UUID id = UUID.randomUUID();
        String safeName = fileName == null ? "source" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.length() > 100) safeName = safeName.substring(safeName.length() - 100);
        jdbc.sql("""
                insert into upload_sessions(id, title_id, creator_id, bucket, object_key, s3_upload_id, file_name, content_type,
                  size_bytes, part_size, part_count, sha256, state, provider, created_at, expires_at, version)
                values (:id, :t, :c, :b, :k, :u, :f, :ct, :s, :s, 1, :sha, 'UPLOADING', 'tus', now(), :exp, 0)""")
            .param("id", id).param("t", titleId).param("c", CurrentUser.id()).param("b", bucket).param("k", "tus-pending/" + id)
            .param("u", id.toString()).param("f", safeName).param("ct", contentType).param("s", sizeBytes).param("sha", sha256)
            .param("exp", java.sql.Timestamp.from(Instant.now().plus(props.sessionTtl()))).update();
        return jdbc.sql("select " + COLS + " from upload_sessions where id = :id").param("id", id).query(UploadSession.class).single();
    }

    /** tus post-finish: verify the stored object and publish. Idempotent for repeated hook deliveries. */
    @Transactional
    public void finishTus(String tusId, String objectKey) {
        UploadSession s = jdbc.sql("select " + COLS + " from upload_sessions where provider = 'tus' and s3_upload_id = :u for update")
                .param("u", tusId).query(UploadSession.class).optional().orElseThrow(() -> ApiException.notFound("Upload"));
        if (s.state().equals("UPLOADED")) return;
        verifyAndPublish(s, objectKey);
    }

    @Transactional
    public UploadSession cancel(UUID id) {
        UploadSession s = get(id);
        if (s.terminal()) return s;
        storage.abortMultipartUpload(s.bucket(), s.objectKey(), s.s3UploadId());
        jdbc.sql("update upload_sessions set state = 'CANCELLED', source_deleted_at = now(), version = version + 1 where id = :id").param("id", id).update();
        return get(id);
    }

    public List<UploadSession> mine() {
        return jdbc.sql("select " + COLS + " from upload_sessions where creator_id = :c order by created_at desc limit 100")
                .param("c", CurrentUser.id()).query(UploadSession.class).list();
    }

    private static String strip(String etag) { return etag == null ? "" : etag.replace("\"", ""); }
}
