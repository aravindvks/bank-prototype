package bank.pbp.thredd.gateway;

import bank.pbp.common.error.PbpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Connector for the Thredd sandbox, to be written against Thredd's card management API
 * (authentication, product and programme IDs, status vocabulary). Fails clearly until then.
 */
@Component
@ConditionalOnProperty(name = "vendor.thredd.mode", havingValue = "sandbox")
public class ThreddSandboxGateway implements ThreddGateway {

    private static PbpException notBuilt(String operation) {
        return new PbpException("VND-UNAVAIL-001", 503, "Vendor unavailable",
                "Thredd sandbox connector is not built yet (" + operation
                        + "): waiting for the Thredd API specification. Set vendor.thredd.mode=simulator.");
    }

    @Override
    public Card issueCard(String productId, String accountRef, String embossName, String idempotencyKey) {
        throw notBuilt("cards.issue");
    }

    @Override
    public void setStatus(String token, String status, String idempotencyKey) {
        throw notBuilt("cards.status");
    }
}
