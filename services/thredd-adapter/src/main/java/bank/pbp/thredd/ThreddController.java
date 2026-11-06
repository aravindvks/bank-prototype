package bank.pbp.thredd;

import bank.pbp.thredd.gateway.ThreddGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal Thredd contract (LLD section 6): card issue and status. Replace, controls, secure
 * display, lifecycle webhooks and settlement files follow in Phase 2.
 */
@RestController
@RequestMapping("/v1")
public class ThreddController {

    private final ThreddGateway thredd;
    private final String productId;

    public ThreddController(ThreddGateway thredd, @Value("${vendor.thredd.product-id}") String productId) {
        this.thredd = thredd;
        this.productId = productId;
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public ThreddGateway.Card issue(@Valid @RequestBody IssueCard req,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return thredd.issueCard(productId, req.accountRef(), req.embossName(), idempotencyKey);
    }

    @PostMapping("/cards/{token}/status")
    public Map<String, String> status(@PathVariable String token, @Valid @RequestBody StatusChange req,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey) {
        thredd.setStatus(token, req.status(), idempotencyKey);
        return Map.of("token", token, "status", req.status());
    }

    @org.springframework.web.bind.annotation.PutMapping("/cards/{token}/controls")
    public ThreddGateway.Controls controls(@PathVariable String token, @Valid @RequestBody ControlsRequest req,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
        ThreddGateway.Controls c = new ThreddGateway.Controls(req.dailyLimit(), req.online(), req.contactless(), req.atm());
        thredd.setControls(token, c, idempotencyKey);
        return c;
    }

    public record ControlsRequest(@NotBlank @Pattern(regexp = "\\d{1,7}\\.\\d{2}") String dailyLimit,
                                  boolean online, boolean contactless, boolean atm) {
    }

    public record IssueCard(@NotBlank String accountRef, @NotBlank String embossName) {
    }

    public record StatusChange(@NotBlank @Pattern(regexp = "ACTIVE|BLOCKED|LOST|CLOSED") String status) {
    }
}
