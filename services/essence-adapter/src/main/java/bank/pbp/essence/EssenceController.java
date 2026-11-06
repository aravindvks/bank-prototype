package bank.pbp.essence;

import bank.pbp.common.error.VendorErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Internal Essence contract (LLD section 6). Phase 0 covers balance and same-bank transfer;
 * parties, accounts, holds, payments and Confirmation of Payee follow in Phase 1.
 */
@RestController
@RequestMapping("/v1")
public class EssenceController {

    private static final Logger log = LoggerFactory.getLogger(EssenceController.class);
    private static final String SCENARIO = "X-Sim-Scenario";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient standard;
    private final RestClient ehi;

    public EssenceController(@Qualifier("essenceStandardClient") RestClient standard,
                             @Qualifier("essenceEhiClient") RestClient ehi) {
        this.standard = standard;
        this.ehi = ehi;
    }

    /** {@code lane=ehi} uses the short timeout for card authorisations. */
    @GetMapping("/accounts/{accountId}/balance")
    public Map<String, Object> balance(@PathVariable String accountId,
                                       @RequestParam(defaultValue = "standard") String lane,
                                       @RequestHeader(value = SCENARIO, required = false) String scenario) {
        RestClient client = "ehi".equals(lane) ? ehi : standard;
        long start = System.nanoTime();
        Map<String, Object> result = VendorErrors.call("essence", "accounts.balance", () -> client.get()
                .uri("/accounts/{id}/balance", accountId)
                .headers(h -> {
                    if (scenario != null) {
                        h.set(SCENARIO, scenario);
                    }
                })
                .retrieve()
                .body(MAP));
        log.info("vendor.call vendor=essence operation=accounts.balance lane={} latencyMs={}",
                lane, (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    @PostMapping("/transfers")
    public Map<String, Object> transfer(@Valid @RequestBody TransferRequest req,
                                        @RequestHeader(value = SCENARIO, required = false) String scenario) {
        long start = System.nanoTime();
        Map<String, Object> vendorRequest = Map.of(
                "debitAccountId", req.debitAccount(),
                "creditSortCode", req.creditor().sortCode(),
                "creditAccountNumber", req.creditor().accountNumber(),
                "amount", req.amount().value(),
                "currency", req.amount().currency(),
                "reference", req.reference() == null ? "" : req.reference(),
                "clientRequestId", req.idempotencyKey());
        Map<String, Object> result = VendorErrors.call("essence", "transfers.create", () -> standard.post()
                .uri("/transfers")
                .headers(h -> {
                    if (scenario != null) {
                        h.set(SCENARIO, scenario);
                    }
                })
                .body(vendorRequest)
                .retrieve()
                .body(MAP));
        log.info("vendor.call vendor=essence operation=transfers.create latencyMs={}",
                (System.nanoTime() - start) / 1_000_000);
        return Map.of("postingRef", String.valueOf(result.get("postingRef")), "status", "POSTED");
    }

    public record Money(@NotBlank @Pattern(regexp = "\\d{1,15}\\.\\d{2}") String value,
                        @NotBlank @Pattern(regexp = "GBP") String currency) {
    }

    public record Creditor(String name,
                           @NotBlank @Pattern(regexp = "\\d{6}") String sortCode,
                           @NotBlank @Pattern(regexp = "\\d{8}") String accountNumber) {
    }

    public record TransferRequest(@NotBlank String debitAccount,
                                  @NotNull @Valid Creditor creditor,
                                  @NotNull @Valid Money amount,
                                  String reference,
                                  @NotBlank String idempotencyKey) {
    }
}
