package bank.pbp.thredd;

import bank.pbp.common.error.VendorErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Internal Thredd contract (LLD section 6). Phase 0: card issue. Status, replace, controls,
 * secure display, lifecycle webhooks and settlement files follow in Phase 1.
 */
@RestController
@RequestMapping("/v1")
public class ThreddController {

    /** Any 13–19 digit run is treated as a possible PAN and refused before logging or storing. */
    private static final Pattern PAN = Pattern.compile("\\b\\d{13,19}\\b");

    private final RestClient thredd;
    private final String productId;

    public ThreddController(RestClient thredd, @Value("${vendor.thredd.product-id}") String productId) {
        this.thredd = thredd;
        this.productId = productId;
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issue(@Valid @RequestBody IssueCard req,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Map<String, Object> vendorRequest = new LinkedHashMap<>();
        vendorRequest.put("productId", productId);
        vendorRequest.put("accountRef", req.accountRef());
        vendorRequest.put("embossName", req.embossName());
        Map<String, Object> card = VendorErrors.call("thredd", "cards.issue", () -> thredd.post()
                .uri("/cards")
                .header("IdempotencyKey", idempotencyKey)
                .body(vendorRequest)
                .retrieve()
                .body(ThreddAdapterApplication.MAP));
        if (card == null || PAN.matcher(card.toString()).find()) {
            throw new IllegalStateException("Thredd response refused: missing or contains a card-number pattern");
        }
        return Map.of("token", card.get("token"), "last4", card.get("last4"), "status", card.get("status"));
    }

    public record IssueCard(@NotBlank String accountRef, @NotBlank String embossName) {
    }
}
