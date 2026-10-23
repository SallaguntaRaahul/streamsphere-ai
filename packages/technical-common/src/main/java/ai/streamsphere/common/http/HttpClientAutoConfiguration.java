package ai.streamsphere.common.http;

import ai.streamsphere.common.security.ServiceTokenClient;
import ai.streamsphere.common.web.CorrelationIdFilter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Propagates correlation ids and applies bounded timeouts to every outbound RestClient. */
@AutoConfiguration
@ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
public class HttpClientAutoConfiguration {

    @Bean
    RestClientCustomizer streamsphereRestClientCustomizer() {
        return b -> {
            var rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(Duration.ofSeconds(2));
            rf.setReadTimeout(Duration.ofSeconds(5));
            b.requestFactory(rf).requestInterceptor((req, body, exec) -> {
                req.getHeaders().set(CorrelationIdFilter.HEADER, CorrelationIdFilter.current());
                return exec.execute(req, body);
            });
        };
    }

    @Bean
    @ConditionalOnProperty("streamsphere.service-auth.client-id")
    ServiceTokenClient serviceTokenClient(RestClient.Builder builder,
            @Value("${streamsphere.service-auth.token-uri}") String tokenUri,
            @Value("${streamsphere.service-auth.client-id}") String clientId,
            @Value("${streamsphere.service-auth.client-secret}") String clientSecret) {
        return new ServiceTokenClient(builder, tokenUri, clientId, clientSecret);
    }
}
