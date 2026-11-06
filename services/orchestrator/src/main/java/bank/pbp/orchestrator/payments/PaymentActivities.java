package bank.pbp.orchestrator.payments;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface PaymentActivities {

    /** Inserts the payment (INITIATED) and its saga row. Idempotent. */
    void createPayment(PaymentCommand command);

    /** Limits and basic checks. Daily limits, customer status and KYC validity join in Phase 1. */
    RiskResult riskCheck(PaymentCommand command);

    /**
     * Moves the payment from one status to the next, writes history and publishes the payment
     * event. Idempotent: does nothing when the payment is no longer in {@code from}.
     */
    void transition(PaymentCommand command, String from, String to, String reason);

    /**
     * Same-bank transfer through the Essence adapter. Returns the Essence posting reference.
     * Throws a non-retryable failure typed with the LLD error code when Essence refuses it.
     */
    String postInternalTransfer(PaymentCommand command);

    record RiskResult(boolean passed, String code, String reason) {
    }
}
