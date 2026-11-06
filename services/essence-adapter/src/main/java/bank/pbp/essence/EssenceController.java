package bank.pbp.essence;

import bank.pbp.essence.gateway.EssenceGateway;
import bank.pbp.essence.gateway.EssenceGateway.Lane;
import bank.pbp.essence.gateway.EssenceGateway.PaymentInstruction;
import bank.pbp.essence.gateway.EssenceGateway.TransferInstruction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Internal Essence contract (LLD section 6). Translation only; no business decisions. */
@RestController
@RequestMapping("/v1")
public class EssenceController {

    private static final Logger log = LoggerFactory.getLogger(EssenceController.class);
    private static final String SCENARIO = "X-Sim-Scenario";

    private final EssenceGateway essence;

    public EssenceController(EssenceGateway essence) {
        this.essence = essence;
    }

    @PostMapping("/parties")
    @ResponseStatus(HttpStatus.CREATED)
    public EssenceGateway.Party createParty(@Valid @RequestBody NewParty req,
                                            @RequestHeader("Idempotency-Key") String key) {
        return timed("parties.create", () -> essence.createParty(req.name(), req.partyType(), key));
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public EssenceGateway.OpenedAccount openAccount(@Valid @RequestBody NewAccount req,
                                                    @RequestHeader("Idempotency-Key") String key) {
        return timed("accounts.open", () -> essence.openAccount(req.partyId(), req.name(),
                req.productCode() == null ? "UK-CURRENT-01" : req.productCode(), key));
    }

    /** {@code lane=ehi} uses the short timeout for card authorisations. */
    @GetMapping("/accounts/{accountId}/balance")
    public EssenceGateway.Balance balance(@PathVariable String accountId,
                                          @RequestParam(defaultValue = "standard") String lane,
                                          @RequestHeader(value = SCENARIO, required = false) String scenario) {
        Lane l = "ehi".equals(lane) ? Lane.EHI : Lane.STANDARD;
        return timed("accounts.balance." + lane, () -> essence.balance(accountId, l, scenario));
    }

    @PostMapping("/accounts/{accountId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public EssenceGateway.Hold placeHold(@PathVariable String accountId, @Valid @RequestBody NewHold req,
                                         @RequestHeader("Idempotency-Key") String key,
                                         @RequestHeader(value = SCENARIO, required = false) String scenario) {
        return timed("holds.place", () -> essence.placeHold(accountId, req.amount().value(), req.amount().currency(),
                req.reference(), key, scenario));
    }

    @PostMapping("/holds/{holdId}/capture")
    public Map<String, String> capture(@PathVariable String holdId, @RequestBody(required = false) Capture req) {
        String postingRef = timed("holds.capture",
                () -> essence.captureHold(holdId, req == null ? null : req.amount()));
        return Map.of("postingRef", postingRef);
    }

    @DeleteMapping("/holds/{holdId}")
    public Map<String, String> release(@PathVariable String holdId) {
        timed("holds.release", () -> {
            essence.releaseHold(holdId);
            return null;
        });
        return Map.of("holdId", holdId, "status", "RELEASED");
    }

    @PostMapping("/transfers")
    public Map<String, String> transfer(@Valid @RequestBody TransferRequest req,
                                        @RequestHeader(value = SCENARIO, required = false) String scenario) {
        String postingRef = timed("transfers.create", () -> essence.transfer(new TransferInstruction(
                req.debitAccount(), req.creditor().sortCode(), req.creditor().accountNumber(), req.amount().value(),
                req.amount().currency(), req.reference(), req.idempotencyKey()), scenario));
        return Map.of("postingRef", postingRef, "status", "POSTED");
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EssenceGateway.PaymentSubmission submitPayment(@Valid @RequestBody PaymentRequest req,
                                                          @RequestHeader(value = SCENARIO, required = false) String scenario) {
        return timed("payments.submit", () -> essence.submitPayment(new PaymentInstruction(req.debitAccount(),
                req.creditor().name(), req.creditor().sortCode(), req.creditor().accountNumber(), req.amount().value(),
                req.amount().currency(), req.reference(), req.endToEndId(), req.idempotencyKey()), scenario));
    }

    private static <T> T timed(String operation, java.util.function.Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            log.info("vendor.call vendor=essence operation={} latencyMs={}", operation,
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    public record Money(@NotBlank @Pattern(regexp = "\\d{1,15}\\.\\d{2}") String value,
                        @NotBlank @Pattern(regexp = "GBP") String currency) {
    }

    public record Creditor(String name,
                           @NotBlank @Pattern(regexp = "\\d{6}") String sortCode,
                           @NotBlank @Pattern(regexp = "\\d{8}") String accountNumber) {
    }

    public record NewParty(@NotBlank String name, @NotBlank @Pattern(regexp = "INDIVIDUAL|BUSINESS") String partyType) {
    }

    public record NewAccount(@NotBlank String partyId, @NotBlank String name, String productCode) {
    }

    public record NewHold(@NotNull @Valid Money amount, String reference) {
    }

    public record Capture(String amount) {
    }

    public record TransferRequest(@NotBlank String debitAccount, @NotNull @Valid Creditor creditor,
                                  @NotNull @Valid Money amount, String reference, @NotBlank String idempotencyKey) {
    }

    public record PaymentRequest(@NotBlank String debitAccount, @NotNull @Valid Creditor creditor,
                                 @NotNull @Valid Money amount, String reference, @NotBlank String endToEndId,
                                 @NotBlank String idempotencyKey) {
    }
}
