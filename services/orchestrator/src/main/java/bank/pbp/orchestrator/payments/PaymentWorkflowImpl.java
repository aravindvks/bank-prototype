package bank.pbp.orchestrator.payments;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Payment saga following the LLD state machine:
 * INITIATED → CHECKS_PASSED → SUBMITTED → COMPLETED, or REJECTED / FAILED.
 *
 * <p>Phase 0 runs same-bank (INTERNAL) payments end to end. Outbound UK rails, where Finastra
 * routes to FPS, CHAPS or Bacs and reports back through the paymentStatus signal, arrive in Phase 1.
 */
public class PaymentWorkflowImpl implements PaymentWorkflow {

    private final PaymentActivities activities = Workflow.newActivityStub(PaymentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(8)
                            .build())
                    .build());

    private String status = "NEW";

    @Override
    public String run(PaymentCommand command) {
        activities.createPayment(command);
        status = "INITIATED";

        PaymentActivities.RiskResult risk = activities.riskCheck(command);
        if (!risk.passed()) {
            return moveTo(command, "REJECTED", risk.code() + ": " + risk.reason());
        }
        moveTo(command, "CHECKS_PASSED", null);

        if (!"INTERNAL".equals(command.paymentType())) {
            return moveTo(command, "REJECTED", "PAY-RAIL-000: outbound UK rails arrive in Phase 1");
        }

        moveTo(command, "SUBMITTED", null);
        try {
            String postingRef = activities.postInternalTransfer(command);
            return moveTo(command, "COMPLETED", "Essence posting " + postingRef);
        } catch (ActivityFailure failure) {
            if (failure.getCause() instanceof ApplicationFailure app && app.isNonRetryable()) {
                return moveTo(command, "REJECTED", app.getType() + ": " + app.getOriginalMessage());
            }
            return moveTo(command, "FAILED", "Essence did not complete the transfer after retries");
        }
    }

    @Override
    public void paymentStatus(String schemeStatus) {
        // Phase 1: outbound rails wait on this signal.
    }

    @Override
    public String currentStatus() {
        return status;
    }

    private String moveTo(PaymentCommand command, String to, String reason) {
        activities.transition(command, status, to, reason);
        status = to;
        return status;
    }
}
