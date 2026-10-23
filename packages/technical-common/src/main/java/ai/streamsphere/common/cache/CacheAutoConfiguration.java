package ai.streamsphere.common.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration(afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
public class CacheAutoConfiguration {
    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    SafeCache safeCache(StringRedisTemplate redis, MeterRegistry meters) {
        return new SafeCache(redis, meters);
    }
}
