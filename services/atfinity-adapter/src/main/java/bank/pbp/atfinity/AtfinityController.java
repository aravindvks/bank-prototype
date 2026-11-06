package bank.pbp.atfinity;

import bank.pbp.atfinity.gateway.AtfinityGateway;
import bank.pbp.common.error.PbpException;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.webhook.WebhookSignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atfinity adapter (LLD section 6): opens cases and turns signed outcome webhooks into kyc-events.
 * Outcome mapping: approved → kyc.approved; referred → kyc.referred; rejected → kyc.rejected;
 * ongoing screening hit → kyc.rescreen.hit.
 */
@RestController
public class AtfinityController {

    private static final Logger log = LoggerFactory.getLogger(AtfinityController.class);

    private final AtfinityGateway atfinity;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final String secret;
    private final long windowSeconds;

    public AtfinityController(AtfinityGateway atfinity, EventPublisher events, ObjectMapper mapper,
                              @Value("${webhook.secret}") String secret,
                              @Value("${webhook.replay-window-seconds}") long windowSeconds) {
        this.atfinity = atfinity;
        this.events = events;
        this.mapper = mapper;
        this.secret = secret;
        this.windowSeconds = windowSeconds;
    }

    @PostMapping("/v1/kyc/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public AtfinityGateway.KycCase openCase(@Valid @RequestBody OpenCase req) {
        return atfinity.openCase(req.customerId(), req.subjectName(), req.workflowCode());
    }

    @PostMapping("/hooks/atfinity")
    public Map<String, String> webhook(@RequestBody String body,
                                       @RequestHeader(value = WebhookSignature.TIMESTAMP_HEADER, required = false) String ts,
                                       @RequestHeader(value = WebhookSignature.SIGNATURE_HEADER, required = false) String sig) {
        if (!WebhookSignature.verify(secret, ts, body, sig, windowSeconds)) {
            throw new PbpException("GEN-AUTH-001", 401, "Webhook signature invalid",
                    "Signature or timestamp check failed");
        }
        JsonNode n;
        try {
            n = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid", "Webhook body is not JSON");
        }
        String type = switch (n.path("type").asText() + ":" + n.path("outcome").asText()) {
            case "case.outcome:approved" -> "kyc.approved";
            case "case.outcome:referred" -> "kyc.referred";
            case "case.outcome:rejected" -> "kyc.rejected";
            case "case.rescreen_hit:hit" -> "kyc.rescreen.hit";
            default -> null;
        };
        if (type == null) {
            log.info("webhook.ignored vendor=atfinity body={}", body);
            return Map.of("status", "IGNORED");
        }
        String customerId = n.path("externalRef").asText();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vendorEventId", n.path("eventId").asText());
        data.put("caseId", n.path("caseId").asText());
        data.put("customerId", customerId);
        data.put("riskRating", n.path("riskRating").asText());
        events.publish(Topics.KYC_EVENTS, EventEnvelope.of(type, "atfinity-adapter", null, null, customerId, data));
        return Map.of("status", "RECEIVED");
    }

    public record OpenCase(@NotBlank String customerId,
                           @NotBlank String subjectName,
                           @NotBlank @Pattern(regexp = "RETAIL_KYC_STANDARD|BUSINESS_KYB_STANDARD") String workflowCode) {
    }
}
