package bank.pbp.thredd;

import bank.pbp.thredd.gateway.ThreddCreditGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal contract for Thredd credit processing (LLD section 6). The orchestrator opens accounts,
 * posts repayments and fees and reads statements; the EHI responder places, captures and releases
 * authorisation holds against open-to-buy. Thredd holds the credit rules.
 */
@RestController
@RequestMapping("/v1/credit")
public class ThreddCreditController {

    static final String MONEY = "\\d{1,9}\\.\\d{2}";

    private final ThreddCreditGateway credit;
    private final String creditProductId;

    public ThreddCreditController(ThreddCreditGateway credit,
                                  @Value("${vendor.thredd.credit-product-id}") String creditProductId) {
        this.credit = credit;
        this.creditProductId = creditProductId;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public ThreddCreditGateway.CreditAccount open(@Valid @RequestBody OpenAccount r,
                                                  @RequestHeader("Idempotency-Key") String key) {
        return credit.open(r.customerRef(), r.creditLimit(), r.purchaseApr(), creditProductId, key);
    }

    @GetMapping("/accounts/{id}")
    public ThreddCreditGateway.CreditAccount get(@PathVariable String id) {
        return credit.get(id);
    }

    @PostMapping("/accounts/{id}/limit")
    public ThreddCreditGateway.CreditAccount limit(@PathVariable String id, @Valid @RequestBody Limit r,
                                                   @RequestHeader("Idempotency-Key") String key) {
        return credit.setLimit(id, r.creditLimit(), key);
    }

    @PostMapping("/accounts/{id}/close")
    public ThreddCreditGateway.CreditAccount close(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
        return credit.close(id, key);
    }

    @PostMapping("/accounts/{id}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> hold(@PathVariable String id, @Valid @RequestBody Money r,
                                    @RequestHeader("Idempotency-Key") String key) {
        return Map.of("holdId", credit.hold(id, r.amount(), r.reference(), key));
    }

    @PostMapping("/holds/{holdId}/capture")
    public Map<String, String> capture(@PathVariable String holdId, @Valid @RequestBody Money r) {
        return Map.of("postingRef", credit.capture(holdId, r.amount()));
    }

    @DeleteMapping("/holds/{holdId}")
    public Map<String, String> release(@PathVariable String holdId) {
        credit.release(holdId);
        return Map.of("holdId", holdId, "status", "RELEASED");
    }

    @PostMapping("/accounts/{id}/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> repay(@PathVariable String id, @Valid @RequestBody Money r,
                                     @RequestHeader("Idempotency-Key") String key) {
        return Map.of("repaymentRef", credit.repay(id, r.amount(), r.reference(), key));
    }

    @PostMapping("/accounts/{id}/fees")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> fee(@PathVariable String id, @Valid @RequestBody Fee r,
                                   @RequestHeader("Idempotency-Key") String key) {
        return Map.of("feeRef", credit.chargeFee(id, r.amount(), r.type(), key));
    }

    @GetMapping("/statements/{statementId}")
    public ThreddCreditGateway.Statement statement(@PathVariable String statementId) {
        return credit.statement(statementId);
    }

    public record OpenAccount(@NotBlank String customerRef, @NotBlank @Pattern(regexp = MONEY) String creditLimit,
                              @NotBlank @Pattern(regexp = "\\d{1,2}(\\.\\d{1,2})?") String purchaseApr) {
    }

    public record Limit(@NotBlank @Pattern(regexp = MONEY) String creditLimit) {
    }

    public record Money(@NotBlank @Pattern(regexp = MONEY) String amount, String reference) {
    }

    public record Fee(@NotBlank @Pattern(regexp = MONEY) String amount,
                      @NotBlank @Pattern(regexp = "LATE_PAYMENT|OVER_LIMIT|CASH_ADVANCE") String type) {
    }
}
