package ai.streamsphere.common.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

@AutoConfiguration
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty("streamsphere.storage.endpoint")
public class StorageAutoConfiguration {

    /** HTTP error mapping only exists in servlet apps (the FFmpeg worker has no web stack). */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    @org.springframework.context.annotation.Import(StorageProblemAdvice.class)
    static class WebErrors { }

    @Bean
    @ConditionalOnMissingBean
    StorageProvider storageProvider(@Value("${streamsphere.storage.provider:minio}") String provider,
                                    @Value("${streamsphere.storage.endpoint}") String endpoint,
                                    @Value("${streamsphere.storage.public-endpoint:${streamsphere.storage.endpoint}}") String publicEndpoint,
                                    @Value("${streamsphere.storage.region:us-east-1}") String region,
                                    @Value("${streamsphere.storage.access-key}") String accessKey,
                                    @Value("${streamsphere.storage.secret-key}") String secretKey) {
        return new S3StorageProvider(provider, endpoint, publicEndpoint, region, accessKey, secretKey);
    }
}
