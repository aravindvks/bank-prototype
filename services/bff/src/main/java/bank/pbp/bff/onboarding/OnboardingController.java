package bank.pbp.bff.onboarding;

import bank.pbp.bff.IdempotencyStore;
import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.VendorErrors;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Sign-up (HLD flow A). POST returns 202 with an onboarding ID at once; the orchestrator runs
 * KYC, account opening and card issue, and GET reports progress.
 */
@RestController
@RequestMapping("/v1/onboarding")
public class OnboardingController {

    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final RestClient orchestrator;

    public OnboardingController(IdempotencyStore idempotency, EventPublisher events, RestClient.Builder builder,
                                @Value("${pbp.services.orchestrator-url}") String orchestratorUrl) {
        this.idempotency = idempotency;
        this.events = events;
        this.orchestrator = builder.clone().baseUrl(orchestratorUrl).build();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody SignUp request,
                                                     @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
        String correlationId = Ids.newCorrelationId(JourneyType.ONB);
        Correlation.set(correlationId);
        Map<String, Object> body = idempotency.once("onboarding:" + idempotencyKey, request, () -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("onboardingId", Ids.uuidV7().toString());
            r.put("status", "STARTED");
            r.put("correlationId", correlationId);
            return r;
        }, r -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("onboardingId", r.get("onboardingId"));
            data.put("customerType", request.customerType());
            data.put("name", request.name());
            data.put("email", request.email());
            // Keyed by onboarding ID until the customer ID exists.
            events.publish(Topics.ONBOARDING_COMMANDS, EventEnvelope.of("onboarding.start", "bff", correlationId,
                    null, String.valueOf(r.get("onboardingId")), data));
        });
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(Correlation.HEADER, String.valueOf(body.get("correlationId")))
                .body(body);
    }

    @GetMapping("/{onboardingId}")
    public JsonNode status(@PathVariable UUID onboardingId) {
        return VendorErrors.call("orchestrator", "onboarding.status", () -> orchestrator.get()
                .uri("/v1/onboarding/{id}", onboardingId).retrieve().body(JsonNode.class));
    }

    public record SignUp(@NotBlank @Pattern(regexp = "INDIVIDUAL|BUSINESS") String customerType,
                         @NotBlank @Size(max = 140) String name,
                         @NotBlank @Email String email) {
    }
}
