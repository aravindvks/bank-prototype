package bank.pbp.credit.gateway;

import bank.pbp.common.error.VendorErrors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "vendor.credit.mode", havingValue = "simulator", matchIfMissing = true)
public class SimulatorCreditDecisionGateway implements CreditDecisionGateway {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient engine;

    public SimulatorCreditDecisionGateway(RestClient.Builder builder, @Value("${vendor.credit.base-url}") String baseUrl,
                                          @Value("${vendor.credit.timeout-ms}") int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(timeoutMs);
        this.engine = builder.clone().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision decide(Application a, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", a.applicationId());
        body.put("name", a.name());
        body.put("product", a.product());
        body.put("annualIncome", a.annualIncome());
        body.put("employmentStatus", a.employmentStatus());
        body.put("monthlyHousing", a.monthlyHousing());
        body.put("monthlyCommitments", a.monthlyCommitments());
        body.put("requestedLimit", a.requestedLimit());
        Map<String, Object> r = VendorErrors.call("credit", "decisions.create", () -> engine.post().uri("/decisions")
                .header("IdempotencyKey", idempotencyKey).body(body).retrieve().body(MAP));
        if (r == null) {
            throw new IllegalStateException("Decision engine returned no body");
        }
        return new Decision(str(r.get("decisionId")), str(r.get("bureauRef")), str(r.get("searchType")),
                str(r.get("outcome")), str(r.get("creditLimit")), str(r.get("purchaseApr")),
                r.get("reasons") == null ? List.of() : ((List<Object>) r.get("reasons")).stream().map(String::valueOf).toList());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
