package ai.streamsphere.common.storage;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Object storage abstraction (S3 API: MinIO locally, Cloudflare R2 in cloud). Presigned URLs are
 * generated against the PUBLIC endpoint so browsers can reach them; data calls use the internal one.
 */
public interface StorageProvider {
    record ObjectMeta(long size, String contentType, String etag) {}
    record Part(int partNumber, String etag, long size) {}

    String providerName();

    String createMultipartUpload(String bucket, String key, String contentType);
    URL presignUploadPart(String bucket, String key, String uploadId, int partNumber, Duration ttl);
    List<Part> listParts(String bucket, String key, String uploadId);
    void completeMultipartUpload(String bucket, String key, String uploadId, List<Part> parts);
    void abortMultipartUpload(String bucket, String key, String uploadId);

    Optional<ObjectMeta> head(String bucket, String key);
    byte[] readRange(String bucket, String key, long from, long toInclusive);
    InputStream open(String bucket, String key);
    void download(String bucket, String key, Path target);
    void upload(String bucket, String key, Path source, String contentType, String cacheControl);
    void put(String bucket, String key, byte[] bytes, String contentType);
    URL presignGet(String bucket, String key, Duration ttl);
    List<String> list(String bucket, String prefix);
    void delete(String bucket, String key);
    int deletePrefix(String bucket, String prefix);
}
