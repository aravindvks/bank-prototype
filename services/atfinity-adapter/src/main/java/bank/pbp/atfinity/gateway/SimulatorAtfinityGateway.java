package bank.pbp.atfinity.gateway;

import bank.pbp.common.error.VendorErrors;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "vendor.atfinity.mode", havingValue = "simulator", matchIfMissing = true)
public class SimulatorAtfinityGateway implements AtfinityGateway {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient atfinity;

    public SimulatorAtfinityGateway(RestClient.Builder builder, @Value("${vendor.atfinity.base-url}") String baseUrl,
                                    @Value("${vendor.atfinity.timeout-ms}") int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(timeoutMs);
        this.atfinity = builder.clone().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    public KycCase openCase(String customerId, String subjectName, String workflowCode) {
        Map<String, Object> body = Map.of("workflowCode", workflowCode,
                "subject", Map.of("name", subjectName, "externalRef", customerId));
        Map<String, Object> r = VendorErrors.call("atfinity", "cases.create", () -> atfinity.post()
                .uri("/cases")
                .body(body)
                .retrieve()
                .body(MAP));
        return new KycCase(String.valueOf(r.get("caseId")), String.valueOf(r.get("status")));
    }
}
