package bank.pbp.orchestrator.payments;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Payment saga following the LLD state machine:
 * INITIATED → CHECKS_PASSED → SUBMITTED → COMPLETED, or REJECTED / FAILED.
 *
 * <p>Same-bank (INTERNAL) payments post directly in Essence. Other-bank payments are submitted to
 * Finastra, which routes to FPS, CHAPS or Bacs and reports back through the paymentStatus signal:
 * SUBMITTED → ACCEPTED → COMPLETED, or REJECTED by the scheme.
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

    /** Wait for a final scheme status (payment.status-timeout.fps, LLD section 13). */
    static final Duration SCHEME_TIMEOUT = Duration.ofMinutes(2);

    private String status = "NEW";
    private final List<String[]> schemeUpdates = new ArrayList<>();

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
            return otherBank(command);
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

    private String otherBank(PaymentCommand command) {
        String finastraRef;
        try {
            finastraRef = activities.submitPayment(command);
        } catch (ActivityFailure failure) {
            if (failure.getCause() instanceof ApplicationFailure app && app.isNonRetryable()) {
                return moveTo(command, "REJECTED", app.getType() + ": " + app.getOriginalMessage());
            }
            return moveTo(command, "FAILED", "Finastra did not accept the payment after retries");
        }
        moveTo(command, "SUBMITTED", "Finastra reference " + finastraRef);
        while (true) {
            boolean updated = Workflow.await(SCHEME_TIMEOUT, () -> !schemeUpdates.isEmpty());
            if (!updated) {
                return moveTo(command, "FAILED",
                        "PAY-STATUS-001: no final scheme status within " + SCHEME_TIMEOUT.toMinutes() + " min");
            }
            String[] update = schemeUpdates.remove(0);
            switch (update[0]) {
                case "ACCEPTED" -> {
                    if ("SUBMITTED".equals(status)) {
                        moveTo(command, "ACCEPTED", null);
                    }
                }
                case "COMPLETED" -> {
                    return moveTo(command, "COMPLETED", "Settled by the scheme");
                }
                case "REJECTED" -> {
                    return moveTo(command, "REJECTED", "PAY-SCHEME-001: " + update[1]);
                }
                default -> {
                    // Unknown scheme statuses are ignored.
                }
            }
        }
    }

    @Override
    public void paymentStatus(String schemeStatus, String reason) {
        schemeUpdates.add(new String[] {schemeStatus, reason});
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
