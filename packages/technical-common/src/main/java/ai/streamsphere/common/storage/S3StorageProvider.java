package ai.streamsphere.common.storage;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

public class S3StorageProvider implements StorageProvider {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String name;

    public S3StorageProvider(String name, String endpoint, String publicEndpoint, String region, String accessKey, String secretKey) {
        this.name = name;
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var cfg = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region)).credentialsProvider(creds)
                .serviceConfiguration(cfg).httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(60)).build())
                .build();
        this.presigner = S3Presigner.builder().endpointOverride(URI.create(publicEndpoint)).region(Region.of(region))
                .credentialsProvider(creds).serviceConfiguration(cfg).build();
    }

    @Override public String providerName() { return name; }

    @Override
    public String createMultipartUpload(String bucket, String key, String contentType) {
        return s3.createMultipartUpload(b -> b.bucket(bucket).key(key).contentType(contentType)).uploadId();
    }

    @Override
    public URL presignUploadPart(String bucket, String key, String uploadId, int partNumber, Duration ttl) {
        return presigner.presignUploadPart(r -> r.signatureDuration(ttl)
                .uploadPartRequest(u -> u.bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber))).url();
    }

    @Override
    public List<Part> listParts(String bucket, String key, String uploadId) {
        List<Part> out = new ArrayList<>();
        Integer marker = null;
        while (true) {
            final Integer m = marker;
            ListPartsResponse r = s3.listParts(b -> { b.bucket(bucket).key(key).uploadId(uploadId).maxParts(1000); if (m != null) b.partNumberMarker(m); });
            r.parts().forEach(p -> out.add(new Part(p.partNumber(), p.eTag(), p.size())));
            if (!Boolean.TRUE.equals(r.isTruncated())) break;
            marker = r.nextPartNumberMarker();
        }
        out.sort(Comparator.comparingInt(Part::partNumber));
        return out;
    }

    @Override
    public void completeMultipartUpload(String bucket, String key, String uploadId, List<Part> parts) {
        var completed = parts.stream().map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.etag()).build()).toList();
        s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()));
    }

    @Override
    public void abortMultipartUpload(String bucket, String key, String uploadId) {
        try { s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)); } catch (NoSuchUploadException ignored) { }
    }

    @Override
    public Optional<ObjectMeta> head(String bucket, String key) {
        try {
            HeadObjectResponse h = s3.headObject(b -> b.bucket(bucket).key(key));
            return Optional.of(new ObjectMeta(h.contentLength(), h.contentType(), h.eTag()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    @Override
    public byte[] readRange(String bucket, String key, long from, long toInclusive) {
        return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key).range("bytes=" + from + "-" + toInclusive)).asByteArray();
    }

    @Override
    public InputStream open(String bucket, String key) { return s3.getObject(b -> b.bucket(bucket).key(key)); }

    @Override
    public void download(String bucket, String key, Path target) {
        s3.getObject(b -> b.bucket(bucket).key(key), target);
    }

    @Override
    public void upload(String bucket, String key, Path source, String contentType, String cacheControl) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).cacheControl(cacheControl), RequestBody.fromFile(source));
    }

    @Override
    public void put(String bucket, String key, byte[] bytes, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
    }

    @Override
    public URL presignGet(String bucket, String key, Duration ttl) {
        return presigner.presignGetObject(r -> r.signatureDuration(ttl).getObjectRequest(g -> g.bucket(bucket).key(key))).url();
    }

    @Override
    public List<String> list(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().forEach(o -> keys.add(o.key()));
        return keys;
    }

    @Override
    public void delete(String bucket, String key) { s3.deleteObject(b -> b.bucket(bucket).key(key)); }

    @Override
    public int deletePrefix(String bucket, String prefix) {
        List<String> keys = list(bucket, prefix);
        for (int i = 0; i < keys.size(); i += 1000) {
            var ids = keys.subList(i, Math.min(keys.size(), i + 1000)).stream().map(k -> ObjectIdentifier.builder().key(k).build()).toList();
            s3.deleteObjects(b -> b.bucket(bucket).delete(Delete.builder().objects(ids).quiet(true).build()));
        }
        return keys.size();
    }
}
