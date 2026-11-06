package bank.pbp.simulator.atfinity;

import bank.pbp.simulator.Scenario;
import bank.pbp.simulator.WebhookSender;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atfinity stand-in. Opens a KYC or KYB case and reports the outcome by signed webhook after
 * {@code sim.atfinity.decision-delay-ms}. Outcome by subject name, so demos are predictable:
 * a name containing "Reject" is rejected, "Refer" is referred, anything else is approved.
 */
@RestController
@RequestMapping("/atfinity/v1")
public class AtfinitySimulator {

    private final WebhookSender webhooks;
    private final String webhookUrl;
    private final long delayMs;
    private final Map<String, String> customerByCase = new ConcurrentHashMap<>();

    public AtfinitySimulator(WebhookSender webhooks, @Value("${sim.atfinity.webhook-url}") String webhookUrl,
                             @Value("${sim.atfinity.decision-delay-ms}") long delayMs) {
        this.webhooks = webhooks;
        this.webhookUrl = webhookUrl;
        this.delayMs = delayMs;
    }

    @PostMapping("/cases")
    public ResponseEntity<Map<String, Object>> openCase(@RequestBody Map<String, Object> request,
                                                        @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        String workflow = String.valueOf(request.getOrDefault("workflowCode", "RETAIL_KYC_STANDARD"));
        String caseId = (workflow.startsWith("BUSINESS") ? "ATF-KYB-" : "ATF-KYC-")
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) request.getOrDefault("subject", Map.of());
        String name = String.valueOf(subject.getOrDefault("name", ""));
        String externalRef = String.valueOf(subject.getOrDefault("externalRef", ""));
        customerByCase.put(caseId, externalRef);

        String outcome = name.contains("Reject") ? "rejected" : name.contains("Refer") ? "referred" : "approved";
        webhooks.send(webhookUrl, outcomeEvent(caseId, externalRef, "case.outcome", outcome,
                "approved".equals(outcome) ? "LOW" : "HIGH"), delayMs);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "caseId", caseId, "workflowCode", workflow, "status", "PENDING"));
    }

    /** Demo toolbar "Sanctions hit": ongoing screening finds a possible match for an approved case. */
    @PostMapping("/sim/cases/{caseId}/rescreen-hit")
    public Map<String, Object> rescreenHit(@PathVariable String caseId) {
        Map<String, Object> event = outcomeEvent(caseId, customerByCase.getOrDefault(caseId, ""),
                "case.rescreen_hit", "hit", "HIGH");
        webhooks.send(webhookUrl, event, 100);
        return event;
    }

    private static Map<String, Object> outcomeEvent(String caseId, String externalRef, String type, String outcome,
                                                    String risk) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", "ATF-EVT-" + UUID.randomUUID().toString().substring(0, 8));
        e.put("type", type);
        e.put("caseId", caseId);
        e.put("externalRef", externalRef);
        e.put("outcome", outcome);
        e.put("riskRating", risk);
        return e;
    }
}
