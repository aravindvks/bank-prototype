package bank.pbp.credit.gateway;

import bank.pbp.common.error.PbpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Connector for the chosen bureau and decision engine. Fails clearly until the vendor is chosen. */
@Component
@ConditionalOnProperty(name = "vendor.credit.mode", havingValue = "sandbox")
public class CreditSandboxGateway implements CreditDecisionGateway {

    @Override
    public Decision decide(Application application, String idempotencyKey) {
        throw new PbpException("VND-UNAVAIL-001", 503, "Vendor unavailable",
                "Credit decision sandbox connector is not built yet: the bureau and decision engine are not chosen."
                        + " Set vendor.credit.mode=simulator.");
    }
}
