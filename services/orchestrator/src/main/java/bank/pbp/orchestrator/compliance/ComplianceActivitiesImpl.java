package bank.pbp.orchestrator.compliance;

import static bank.pbp.orchestrator.temporal.ActivitySupport.call;
import static bank.pbp.orchestrator.temporal.ActivitySupport.withCorrelation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ComplianceActivitiesImpl implements ComplianceWorkflow.ComplianceActivities {

    private static final Logger log = LoggerFactory.getLogger(ComplianceActivitiesImpl.class);

    private final RestClient mapping;
    private final RestClient thredd;

    public ComplianceActivitiesImpl(@Qualifier("mappingClient") RestClient mapping,
                                    @Qualifier("threddClient") RestClient thredd) {
        this.mapping = mapping;
        this.thredd = thredd;
    }

    @Override
    public boolean freezeCustomer(String customerId, String correlationId, String reason) {
        return withCorrelation(correlationId, () -> {
            JsonNode customer = call("mapping.customers.get", () -> mapping.get()
                    .uri("/v1/customers/{id}", customerId).retrieve().body(JsonNode.class));
            if (!"ACTIVE".equals(customer.path("status").asText())) {
                return false;
            }
            call("mapping.customers.status", () -> mapping.patch().uri("/v1/customers/{id}/status", customerId)
                    .header("X-Actor", "svc:orchestrator-compliance")
                    .body(Map.of("status", "FROZEN", "reason", reason)).retrieve().toBodilessEntity());
            log.info("compliance.frozen customerId={} reason={}", customerId, reason);
            return true;
        });
    }

    @Override
    public int blockCards(String customerId, String correlationId) {
        return withCorrelation(correlationId, () -> {
            JsonNode graph = call("mapping.customers.graph", () -> mapping.get()
                    .uri("/v1/customers/{id}/graph", customerId).retrieve().body(JsonNode.class));
            int blocked = 0;
            for (JsonNode ref : graph.path("refs")) {
                if (!"CARD".equals(ref.path("entityType").asText())) {
                    continue;
                }
                String token = ref.path("externalId").asText();
                call("thredd.cards.status", () -> thredd.post().uri("/v1/cards/{token}/status", token)
                        .header("Idempotency-Key", "freeze:" + correlationId + ":" + token)
                        .body(Map.of("status", "BLOCKED")).retrieve().toBodilessEntity());
                blocked++;
            }
            return blocked;
        });
    }
}
