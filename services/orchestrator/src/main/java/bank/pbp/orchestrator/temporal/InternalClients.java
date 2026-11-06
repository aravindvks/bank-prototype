package bank.pbp.orchestrator.temporal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** HTTP clients for the services the sagas call. Correlation headers are added by platform-common. */
@Configuration
public class InternalClients {

    @Bean
    RestClient mappingClient(RestClient.Builder b, @Value("${pbp.services.mapping-url}") String url) {
        return build(b, url);
    }

    @Bean
    RestClient atfinityClient(RestClient.Builder b, @Value("${pbp.services.atfinity-adapter-url}") String url) {
        return build(b, url);
    }

    @Bean
    RestClient essenceClient(RestClient.Builder b, @Value("${pbp.adapters.essence-url}") String url) {
        return build(b, url);
    }

    @Bean
    RestClient threddClient(RestClient.Builder b, @Value("${pbp.services.thredd-adapter-url}") String url) {
        return build(b, url);
    }

    private static RestClient build(RestClient.Builder b, String url) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(12_000);
        return b.clone().baseUrl(url).requestFactory(f).build();
    }
}
