package bank.pbp.essence;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.PbpException;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import bank.pbp.common.webhook.WebhookSignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finastra notification intake (LLD section 6): payment status and inbound credits.
 * Checks the signature, normalises to our vocabulary and publishes to payment-events:
 * {@code payment.scheme_status} and {@code credit.received}. The orchestrator decides what they mean.
 */
@RestController
public class FinastraWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FinastraWebhookController.class);

    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final String secret;
    private final long windowSeconds;

    public FinastraWebhookController(EventPublisher events, ObjectMapper mapper,
                                     @Value("${webhook.secret}") String secret,
                                     @Value("${webhook.replay-window-seconds}") long windowSeconds) {
        this.events = events;
        this.mapper = mapper;
        this.secret = secret;
        this.windowSeconds = windowSeconds;
    }

    @PostMapping("/hooks/finastra")
    public Map<String, String> receive(@RequestBody String body,
                                       @RequestHeader(value = WebhookSignature.TIMESTAMP_HEADER, required = false) String ts,
                                       @RequestHeader(value = WebhookSignature.SIGNATURE_HEADER, required = false) String sig) {
        if (!WebhookSignature.verify(secret, ts, body, sig, windowSeconds)) {
            throw new PbpException("GEN-AUTH-001", 401, "Webhook signature invalid",
                    "Signature or timestamp check failed");
        }
        JsonNode n = read(body);
        String type = n.path("type").asText();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vendorEventId", n.path("eventId").asText());
        data.put("finastraRef", n.path("finastraRef").asText());
        data.put("scheme", n.path("scheme").asText());

        switch (type) {
            case "payment.status" -> {
                // endToEndId is our payment ID, echoed by Finastra.
                data.put("paymentId", n.path("endToEndId").asText());
                data.put("status", n.path("status").asText());
                data.put("reason", n.path("reason").isNull() ? null : n.path("reason").asText(null));
                events.publish(Topics.PAYMENT_EVENTS,
                        EventEnvelope.of("payment.scheme_status", "essence-adapter", null, null, null, data));
            }
            case "credit.received" -> {
                String correlationId = Ids.newCorrelationId(JourneyType.PAY);
                Correlation.set(correlationId);
                data.put("creditAccount", n.path("creditAccountId").asText());
                data.put("amount", Map.of("value", n.path("amount").asText(), "currency", n.path("currency").asText("GBP")));
                data.put("debtorName", n.path("debtorName").asText());
                data.put("debtorSortCode", n.path("debtorSortCode").asText());
                data.put("reference", n.path("reference").asText());
                events.publish(Topics.PAYMENT_EVENTS,
                        EventEnvelope.of("credit.received", "essence-adapter", correlationId, null, null, data));
            }
            default -> log.info("webhook.ignored vendor=finastra type={}", type);
        }
        return Map.of("status", "RECEIVED");
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid", "Webhook body is not JSON");
        }
    }
}
