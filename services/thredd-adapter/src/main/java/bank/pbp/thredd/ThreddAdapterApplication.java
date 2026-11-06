package bank.pbp.thredd;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class ThreddAdapterApplication {

    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    public static void main(String[] args) {
        SpringApplication.run(ThreddAdapterApplication.class, args);
    }

    @Bean
    RestClient threddClient(RestClient.Builder builder,
                            @Value("${vendor.thredd.base-url}") String baseUrl,
                            @Value("${vendor.thredd.timeout-ms}") int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(timeoutMs);
        return builder.clone().baseUrl(baseUrl).requestFactory(f).build();
    }
}
