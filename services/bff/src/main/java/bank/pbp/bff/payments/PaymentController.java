package bank.pbp.bff.payments;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer payment entry point. Returns 202 Accepted at once; the orchestrator runs the
 * payment and the result reaches the app as a pushed event (Phase 1).
 *
 * <p>Phase 0 takes the customer from X-Customer-Id. Identity Domains login and token
 * validation at the API gateway replace this header in Phase 2.
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody PaymentRequest request,
                                                      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                      @RequestHeader("X-Customer-Id") UUID customerId) {
        String correlationId = Correlation.current()
                .filter(id -> id.startsWith("COR-PAY-"))
                .orElseGet(() -> Ids.newCorrelationId(JourneyType.PAY));
        Correlation.set(correlationId);
        Map<String, Object> body = payments.accept(customerId, idempotencyKey.toString(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(Correlation.HEADER, String.valueOf(body.get("correlationId")))
                .body(body);
    }
}
