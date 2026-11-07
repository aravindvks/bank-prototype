package bank.pbp.thredd;

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
 * Thredd notifications (LLD section 6). Checks the signature, normalises and publishes to card-events:
 * {@code credit.statement} → {@code creditcard.statement_issued}. The orchestrator decides what it means.
 */
@RestController
public class ThreddWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ThreddWebhookController.class);

    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final String secret;
    private final long windowSeconds;

    public ThreddWebhookController(EventPublisher events, ObjectMapper mapper, @Value("${webhook.secret}") String secret,
                                   @Value("${webhook.replay-window-seconds}") long windowSeconds) {
        this.events = events;
        this.mapper = mapper;
        this.secret = secret;
        this.windowSeconds = windowSeconds;
    }

    @PostMapping("/hooks/thredd")
    public Map<String, String> receive(@RequestBody String body,
                                       @RequestHeader(value = WebhookSignature.TIMESTAMP_HEADER, required = false) String ts,
                                       @RequestHeader(value = WebhookSignature.SIGNATURE_HEADER, required = false) String sig) {
        if (!WebhookSignature.verify(secret, ts, body, sig, windowSeconds)) {
            throw new PbpException("GEN-AUTH-001", 401, "Webhook signature invalid", "Signature or timestamp check failed");
        }
        JsonNode n = read(body);
        String type = n.path("type").asText();
        if ("credit.statement".equals(type)) {
            String correlationId = Ids.newCorrelationId(JourneyType.CRD);
            Correlation.set(correlationId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("vendorEventId", n.path("eventId").asText());
            for (String f : new String[] {"statementId", "accountId", "statementDate", "balance", "minimumPayment",
                    "interest", "fees", "dueDate", "dueAt"}) {
                data.put(f, n.path(f).asText(null));
            }
            events.publish(Topics.CARD_EVENTS, EventEnvelope.of("creditcard.statement_issued", "thredd-adapter",
                    correlationId, null, null, data));
        } else {
            log.info("webhook.ignored vendor=thredd type={}", type);
        }
        return Map.of("status", "RECEIVED");
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new PbpException("GEN-VALID-001", 400, "Invalid webhook", "Body is not JSON");
        }
    }
}
