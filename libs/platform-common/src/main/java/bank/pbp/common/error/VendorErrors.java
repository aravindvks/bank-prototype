package bank.pbp.common.error;

import java.util.function.Supplier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Maps vendor failures to the stable codes in LLD section 14. Adapters never make business decisions. */
public final class VendorErrors {

    private VendorErrors() {
    }

    public static <T> T call(String vendor, String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw new PbpException("VND-TIMEOUT-001", 504, "Vendor timed out",
                    vendor + " did not answer " + operation + " in time", true);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String body = e.getResponseBodyAsString();
            if (status >= 500) {
                throw new PbpException("VND-UNAVAIL-001", 503, "Vendor unavailable",
                        vendor + " returned " + status + " for " + operation, true);
            }
            // Thredd credit processing: the credit account's rules, in our vocabulary (LLD section 14).
            if (body.contains("CARD-CREDIT-001")) {
                throw new PbpException("CARD-CREDIT-001", 422, "Over credit limit",
                        "Available credit is below the amount");
            }
            if (body.contains("CARD-CREDIT-002")) {
                throw new PbpException("CARD-CREDIT-002", 422, "Credit account not open",
                        "The credit card account is not open for spending");
            }
            if (body.contains("CARD-CREDIT-003")) {
                throw new PbpException("CARD-CREDIT-003", 409, "Balance outstanding",
                        "The credit card account can only close with a zero balance");
            }
            if (body.contains("INSUFFICIENT_FUNDS")) {
                throw new PbpException("PAY-FUNDS-001", 422, "Insufficient funds",
                        "Available balance is below the amount");
            }
            throw new PbpException("VND-REJECT-001", 502, "Vendor rejected the request",
                    vendor + " rejected " + operation + " with " + status + ": " + body);
        }
    }
}
