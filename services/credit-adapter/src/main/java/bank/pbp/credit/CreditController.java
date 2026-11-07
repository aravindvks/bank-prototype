package bank.pbp.credit;

import bank.pbp.credit.gateway.CreditDecisionGateway;
import io.micrometer.core.instrument.Metrics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal credit decision contract (LLD section 6). A soft search only: no footprint on the file. */
@RestController
@RequestMapping("/v1")
public class CreditController {

    private final CreditDecisionGateway engine;

    public CreditController(CreditDecisionGateway engine) {
        this.engine = engine;
    }

    @PostMapping("/decisions")
    public CreditDecisionGateway.Decision decide(@Valid @RequestBody DecisionRequest r,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey) {
        CreditDecisionGateway.Decision d = engine.decide(new CreditDecisionGateway.Application(r.applicationId(),
                r.customerId(), r.name(), r.product(), r.annualIncome(), r.employmentStatus(), r.monthlyHousing(),
                r.monthlyCommitments(), r.requestedLimit()), idempotencyKey);
        Metrics.counter("pbp.credit.decisions", "outcome", d.outcome() == null ? "NONE" : d.outcome()).increment();
        return d;
    }

    static final String MONEY = "\\d{1,9}(\\.\\d{2})?";

    public record DecisionRequest(@NotBlank String applicationId, @NotBlank String customerId, String name,
                                  @NotBlank String product, @NotBlank @Pattern(regexp = MONEY) String annualIncome,
                                  @NotBlank @Pattern(regexp = "EMPLOYED|SELF_EMPLOYED|RETIRED|STUDENT|UNEMPLOYED")
                                  String employmentStatus,
                                  @NotBlank @Pattern(regexp = MONEY) String monthlyHousing,
                                  @NotBlank @Pattern(regexp = MONEY) String monthlyCommitments,
                                  @NotBlank @Pattern(regexp = MONEY) String requestedLimit) {
    }
}
