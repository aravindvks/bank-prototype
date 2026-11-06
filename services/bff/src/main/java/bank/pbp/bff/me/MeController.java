package bank.pbp.bff.me;

import bank.pbp.bff.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import bank.pbp.common.error.VendorErrors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Home screen data in one call: customer status, current account with live balance, and card.
 * Composed from the mapping service (who owns what) and the Essence adapter (balance).
 */
@RestController
public class MeController {

    private final RestClient mapping;
    private final RestClient essence;
    private final ObjectMapper mapper;
    private final Caller caller;

    public MeController(RestClient.Builder builder, ObjectMapper mapper, Caller caller,
                        @Value("${pbp.services.mapping-url}") String mappingUrl,
                        @Value("${pbp.services.essence-adapter-url}") String essenceUrl) {
        this.mapping = builder.clone().baseUrl(mappingUrl).build();
        this.essence = builder.clone().baseUrl(essenceUrl).build();
        this.mapper = mapper;
        this.caller = caller;
    }

    @GetMapping("/v1/me")
    public Map<String, Object> me(HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        JsonNode graph = VendorErrors.call("mapping", "customers.graph", () -> mapping.get()
                .uri("/v1/customers/{id}/graph", customerId).retrieve().body(JsonNode.class));
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode customer = graph.path("customer");
        out.put("customerId", customer.path("customerId").asText());
        out.put("customerType", customer.path("customerType").asText());
        out.put("status", customer.path("status").asText());
        out.put("kycStatus", customer.path("kycStatus").asText(null));
        out.put("riskRating", customer.path("riskRating").asText(null));

        for (JsonNode ref : graph.path("refs")) {
            switch (ref.path("entityType").asText()) {
                case "ACCOUNT" -> {
                    String accountId = ref.path("externalId").asText();
                    Map<String, Object> account = new LinkedHashMap<>();
                    account.put("accountId", accountId);
                    account.put("name", ref.path("metadata").path("name").asText(null));
                    account.put("sortCode", ref.path("metadata").path("sortCode").asText(null));
                    account.put("accountNumber", ref.path("metadata").path("accountNumber").asText(null));
                    try {
                        JsonNode balance = essence.get().uri("/v1/accounts/{id}/balance", accountId)
                                .retrieve().body(JsonNode.class);
                        account.put("available", balance.path("available").asText());
                        account.put("ledger", balance.path("ledger").asText());
                    } catch (RuntimeException e) {
                        account.put("available", null);
                        account.put("balanceError", "Balance unavailable right now");
                    }
                    out.put("account", account);
                }
                case "CARD" -> {
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("token", ref.path("externalId").asText());
                    card.put("last4", ref.path("metadata").path("last4").asText(null));
                    card.put("status", ref.path("status").asText());
                    card.put("dailyLimit", ref.path("metadata").path("dailyLimit").asText("300.00"));
                    card.put("online", ref.path("metadata").path("online").asBoolean(true));
                    card.put("contactless", ref.path("metadata").path("contactless").asBoolean(true));
                    card.put("atm", ref.path("metadata").path("atm").asBoolean(true));
                    out.put("card", card);
                }
                case "KYC_CASE" -> out.put("kycCaseId", ref.path("externalId").asText());
                default -> {
                    // The Essence party is not shown on the home screen.
                }
            }
        }
        return out;
    }
}
