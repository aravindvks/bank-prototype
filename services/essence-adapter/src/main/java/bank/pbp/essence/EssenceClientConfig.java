package bank.pbp.essence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Two lanes with separate timeouts (LLD sections 5 and 6): the EHI lane must answer inside
 * Thredd's limit, the standard lane can wait up to 10 s.
 */
@Configuration
public class EssenceClientConfig {

    @Bean
    RestClient essenceStandardClient(RestClient.Builder builder,
                                     @Value("${vendor.essence.base-url}") String baseUrl,
                                     @Value("${vendor.essence.timeout.connect-ms}") int connectMs,
                                     @Value("${vendor.essence.timeout.standard-ms}") int readMs) {
        return builder.clone().baseUrl(baseUrl).requestFactory(factory(connectMs, readMs)).build();
    }

    @Bean
    RestClient essenceEhiClient(RestClient.Builder builder,
                                @Value("${vendor.essence.base-url}") String baseUrl,
                                @Value("${vendor.essence.timeout.connect-ms}") int connectMs,
                                @Value("${vendor.essence.timeout.ehi-ms}") int readMs) {
        return builder.clone().baseUrl(baseUrl).requestFactory(factory(connectMs, readMs)).build();
    }

    private static SimpleClientHttpRequestFactory factory(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return f;
    }
}
