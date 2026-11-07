package bank.pbp.thredd.gateway;

import bank.pbp.common.error.PbpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Connector for Thredd's credit processing API. Fails clearly until the specification arrives. */
@Component
@ConditionalOnProperty(name = "vendor.thredd.mode", havingValue = "sandbox")
public class ThreddCreditSandboxGateway implements ThreddCreditGateway {

    private static PbpException notBuilt(String operation) {
        return new PbpException("VND-UNAVAIL-001", 503, "Vendor unavailable",
                "Thredd credit sandbox connector is not built yet (" + operation
                        + "): waiting for the Thredd credit API specification. Set vendor.thredd.mode=simulator.");
    }

    @Override public CreditAccount open(String c, String l, String a, String p, String k) { throw notBuilt("credit.accounts.open"); }
    @Override public CreditAccount get(String id) { throw notBuilt("credit.accounts.get"); }
    @Override public CreditAccount setLimit(String id, String l, String k) { throw notBuilt("credit.accounts.limit"); }
    @Override public CreditAccount close(String id, String k) { throw notBuilt("credit.accounts.close"); }
    @Override public String hold(String id, String a, String r, String k) { throw notBuilt("credit.holds.create"); }
    @Override public String capture(String h, String a) { throw notBuilt("credit.holds.capture"); }
    @Override public void release(String h) { throw notBuilt("credit.holds.release"); }
    @Override public String repay(String id, String a, String r, String k) { throw notBuilt("credit.repayments.create"); }
    @Override public String chargeFee(String id, String a, String t, String k) { throw notBuilt("credit.fees.create"); }
    @Override public Statement statement(String id) { throw notBuilt("credit.statements.get"); }
}
