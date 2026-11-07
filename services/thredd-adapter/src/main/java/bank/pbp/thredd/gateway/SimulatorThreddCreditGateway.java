package bank.pbp.thredd.gateway;

import bank.pbp.common.error.VendorErrors;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "vendor.thredd.mode", havingValue = "simulator", matchIfMissing = true)
public class SimulatorThreddCreditGateway implements ThreddCreditGateway {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient thredd;

    public SimulatorThreddCreditGateway(RestClient.Builder builder, @Value("${vendor.thredd.base-url}") String baseUrl,
                                        @Value("${vendor.thredd.timeout-ms}") int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(timeoutMs);
        this.thredd = builder.clone().baseUrl(baseUrl + "/credit").requestFactory(f).build();
    }

    @Override
    public CreditAccount open(String customerRef, String creditLimit, String purchaseApr, String productId,
                              String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerRef", customerRef);
        body.put("creditLimit", creditLimit);
        body.put("purchaseApr", purchaseApr);
        body.put("productId", productId);
        return account(VendorErrors.call("thredd", "credit.accounts.open", () -> thredd.post().uri("/accounts")
                .header("IdempotencyKey", idempotencyKey).body(body).retrieve().body(MAP)));
    }

    @Override
    public CreditAccount get(String accountId) {
        return account(VendorErrors.call("thredd", "credit.accounts.get", () -> thredd.get()
                .uri("/accounts/{id}", accountId).retrieve().body(MAP)));
    }

    @Override
    public CreditAccount setLimit(String accountId, String creditLimit, String idempotencyKey) {
        return account(VendorErrors.call("thredd", "credit.accounts.limit", () -> thredd.post()
                .uri("/accounts/{id}/limit", accountId).header("IdempotencyKey", idempotencyKey)
                .body(Map.of("creditLimit", creditLimit)).retrieve().body(MAP)));
    }

    @Override
    public CreditAccount close(String accountId, String idempotencyKey) {
        return account(VendorErrors.call("thredd", "credit.accounts.close", () -> thredd.post()
                .uri("/accounts/{id}/close", accountId).header("IdempotencyKey", idempotencyKey)
                .retrieve().body(MAP)));
    }

    @Override
    public String hold(String accountId, String amount, String reference, String idempotencyKey) {
        Map<String, Object> r = VendorErrors.call("thredd", "credit.holds.create", () -> thredd.post()
                .uri("/accounts/{id}/holds", accountId).header("IdempotencyKey", idempotencyKey)
                .body(Map.of("amount", amount, "reference", reference == null ? "Card" : reference))
                .retrieve().body(MAP));
        return str(r == null ? null : r.get("holdId"));
    }

    @Override
    public String capture(String holdId, String amount) {
        Map<String, Object> r = VendorErrors.call("thredd", "credit.holds.capture", () -> thredd.post()
                .uri("/holds/{id}/capture", holdId).body(Map.of("amount", amount)).retrieve().body(MAP));
        return str(r == null ? null : r.get("postingRef"));
    }

    @Override
    public void release(String holdId) {
        VendorErrors.call("thredd", "credit.holds.release", () -> thredd.delete()
                .uri("/holds/{id}", holdId).retrieve().toBodilessEntity());
    }

    @Override
    public String repay(String accountId, String amount, String reference, String idempotencyKey) {
        Map<String, Object> r = VendorErrors.call("thredd", "credit.repayments.create", () -> thredd.post()
                .uri("/accounts/{id}/repayments", accountId).header("IdempotencyKey", idempotencyKey)
                .body(Map.of("amount", amount, "reference", reference == null ? "Repayment" : reference))
                .retrieve().body(MAP));
        return str(r == null ? null : r.get("repaymentRef"));
    }

    @Override
    public String chargeFee(String accountId, String amount, String type, String idempotencyKey) {
        Map<String, Object> r = VendorErrors.call("thredd", "credit.fees.create", () -> thredd.post()
                .uri("/accounts/{id}/fees", accountId).header("IdempotencyKey", idempotencyKey)
                .body(Map.of("amount", amount, "type", type)).retrieve().body(MAP));
        return str(r == null ? null : r.get("feeRef"));
    }

    @Override
    public Statement statement(String statementId) {
        return Statement.from(VendorErrors.call("thredd", "credit.statements.get", () -> thredd.get()
                .uri("/statements/{id}", statementId).retrieve().body(MAP)));
    }

    private static CreditAccount account(Map<String, Object> m) {
        if (m == null) {
            throw new IllegalStateException("Thredd returned no credit account");
        }
        return new CreditAccount(str(m.get("accountId")), str(m.get("status")), str(m.get("creditLimit")),
                str(m.get("purchaseApr")), str(m.get("balance")), str(m.get("pending")), str(m.get("availableCredit")),
                Statement.from(m.get("lastStatement")), m.get("transactions"));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
