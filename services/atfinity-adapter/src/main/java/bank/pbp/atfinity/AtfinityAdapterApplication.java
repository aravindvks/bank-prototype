package bank.pbp.atfinity;

import bank.pbp.common.error.VendorErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Atfinity adapter (LLD section 6). Phase 0: open a KYC or KYB case. Documents, case status,
 * review cases and the HMAC-verified outcome webhook follow in Phase 1.
 */
@SpringBootApplication
@RestController
public class AtfinityAdapterApplication {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient atfinity;

    public AtfinityAdapterApplication(RestClient.Builder builder,
                                      @Value("${vendor.atfinity.base-url}") String baseUrl,
                                      @Value("${vendor.atfinity.timeout-ms}") int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(timeoutMs);
        this.atfinity = builder.clone().baseUrl(baseUrl).requestFactory(f).build();
    }

    public static void main(String[] args) {
        SpringApplication.run(AtfinityAdapterApplication.class, args);
    }

    @PostMapping("/v1/kyc/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> openCase(@Valid @RequestBody OpenCase req) {
        Map<String, Object> vendorRequest = Map.of(
                "workflowCode", req.workflowCode(),
                "subject", Map.of("name", req.subjectName(), "externalRef", req.customerId()));
        return VendorErrors.call("atfinity", "cases.create", () -> atfinity.post()
                .uri("/cases")
                .body(vendorRequest)
                .retrieve()
                .body(MAP));
    }

    public record OpenCase(@NotBlank String customerId,
                           @NotBlank String subjectName,
                           @NotBlank @Pattern(regexp = "RETAIL_KYC_STANDARD|BUSINESS_KYB_STANDARD") String workflowCode) {
    }
}
