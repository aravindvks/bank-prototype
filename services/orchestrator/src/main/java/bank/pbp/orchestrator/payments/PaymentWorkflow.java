package bank.pbp.orchestrator.payments;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Payment saga. Workflow ID is {@code payment-<paymentId>}, so a repeated command cannot start it twice. */
@WorkflowInterface
public interface PaymentWorkflow {

    /** Returns the final payment status. */
    @WorkflowMethod
    String run(PaymentCommand command);

    /** Scheme status from Finastra (ACCEPTED, COMPLETED, REJECTED). Used by outbound rails in Phase 1. */
    @SignalMethod
    void paymentStatus(String status);

    @QueryMethod
    String currentStatus();
}
