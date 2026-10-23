package ai.streamsphere.upload;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("streamsphere.upload")
public record UploadProperties(
        String bucket,
        long maxBytes,
        long partSizeBytes,
        Duration partUrlTtl,
        Duration sessionTtl,
        Duration sourceRetentionAfterReady,
        Set<String> allowedContentTypes,
        long storageQuotaBytes,
        double stopRatio) {}
