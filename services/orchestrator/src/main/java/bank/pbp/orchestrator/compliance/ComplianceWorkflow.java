package bank.pbp.orchestrator.compliance;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;

/**
 * Compliance saga (HLD flow: sanctions hit). Atfinity's ongoing screening reports a possible
 * match; the customer is frozen (cache updater pushes CUSTOMER_FROZEN to the EHI responder within
 * about 5 s) and every card is blocked at Thredd. Staff review and unfreeze arrive with the staff
 * portal in Phase 2. Workflow ID {@code compliance-<vendor event ID>}; task queue "compliance".
 */
@WorkflowInterface
public interface ComplianceWorkflow {

    @WorkflowMethod
    String freeze(String customerId, String correlationId, String reason);

    @ActivityInterface
    interface ComplianceActivities {

        /** ACTIVE → FROZEN in the mapping service. Returns false if the customer was not ACTIVE. */
        boolean freezeCustomer(String customerId, String correlationId, String reason);

        /** Blocks every card of the customer at Thredd. Returns how many. */
        int blockCards(String customerId, String correlationId);
    }

    class Impl implements ComplianceWorkflow {

        private final ComplianceActivities activities = Workflow.newActivityStub(ComplianceActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(20))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setInitialInterval(Duration.ofSeconds(1))
                                .setBackoffCoefficient(2.0)
                                .setMaximumAttempts(10)
                                .build())
                        .build());

        @Override
        public String freeze(String customerId, String correlationId, String reason) {
            boolean frozen = activities.freezeCustomer(customerId, correlationId, reason);
            int blocked = activities.blockCards(customerId, correlationId);
            return (frozen ? "FROZEN" : "UNCHANGED") + ", cards blocked: " + blocked;
        }
    }
}
