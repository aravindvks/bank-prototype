package bank.pbp.essence.gateway;

import bank.pbp.common.error.PbpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Connector for the Finastra Fusion Essence sandbox. To be written against the Essence API
 * specification (OAuth2 client credentials, token cached until 60 s before expiry, separate EHI
 * and standard connection pools, Finastra status vocabulary mapped to ours). Until then every call
 * fails clearly instead of pretending to work.
 */
@Component
@ConditionalOnProperty(name = "vendor.essence.mode", havingValue = "sandbox")
public class FinastraSandboxEssenceGateway implements EssenceGateway {

    private static PbpException notBuilt(String operation) {
        return new PbpException("VND-UNAVAIL-001", 503, "Vendor unavailable",
                "Finastra sandbox connector is not built yet (" + operation
                        + "): waiting for the Essence API specification. Set vendor.essence.mode=simulator.");
    }

    @Override
    public Party createParty(String name, String partyType, String idempotencyKey) {
        throw notBuilt("parties.create");
    }

    @Override
    public OpenedAccount openAccount(String partyId, String name, String productCode, String idempotencyKey) {
        throw notBuilt("accounts.open");
    }

    @Override
    public Balance balance(String accountId, Lane lane, String scenario) {
        throw notBuilt("accounts.balance");
    }

    @Override
    public Hold placeHold(String accountId, String amount, String currency, String reference, String idempotencyKey,
                          String scenario) {
        throw notBuilt("holds.place");
    }

    @Override
    public String captureHold(String holdId, String amount) {
        throw notBuilt("holds.capture");
    }

    @Override
    public void releaseHold(String holdId) {
        throw notBuilt("holds.release");
    }

    @Override
    public String transfer(TransferInstruction instruction, String scenario) {
        throw notBuilt("transfers.create");
    }

    @Override
    public PaymentSubmission submitPayment(PaymentInstruction instruction, String scenario) {
        throw notBuilt("payments.submit");
    }

    @Override
    public CopResult confirmPayee(String name, String sortCode, String accountNumber, String scenario) {
        throw notBuilt("payees.confirm");
    }

    @Override
    public String chargeFee(String accountId, String amount, String currency, String reference, String idempotencyKey) {
        throw notBuilt("fees.charge");
    }

    @Override
    public String payCollection(String collectionRef) {
        throw notBuilt("directdebits.pay");
    }

    @Override
    public void returnCollection(String collectionRef, String reasonCode) {
        throw notBuilt("directdebits.return");
    }

    @Override
    public String indemnityClaim(String collectionRef, String reasonCode) {
        throw notBuilt("directdebits.indemnity");
    }

    @Override
    public void cancelMandate(String mandateRef, String reasonCode) {
        throw notBuilt("directdebits.cancel");
    }

    @Override
    public java.util.List<Posting> postings(String date) {
        throw notBuilt("postings.list");
    }
}
