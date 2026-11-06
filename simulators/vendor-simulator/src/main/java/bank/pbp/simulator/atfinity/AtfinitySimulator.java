package bank.pbp.simulator.atfinity;

import bank.pbp.simulator.Scenario;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atfinity stand-in: opens a KYC or KYB case. Outcome webhooks (approve, refer, reject,
 * rescreen hit) are added in Phase 1 with the onboarding saga.
 */
@RestController
@RequestMapping("/atfinity/v1")
public class AtfinitySimulator {

    @PostMapping("/cases")
    public ResponseEntity<Map<String, Object>> openCase(@RequestBody Map<String, Object> request,
                                                        @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        String workflow = String.valueOf(request.getOrDefault("workflowCode", "RETAIL_KYC_STANDARD"));
        String prefix = workflow.startsWith("BUSINESS") ? "ATF-KYB-" : "ATF-KYC-";
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "caseId", prefix + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "workflowCode", workflow,
                "status", "PENDING"));
    }
}
