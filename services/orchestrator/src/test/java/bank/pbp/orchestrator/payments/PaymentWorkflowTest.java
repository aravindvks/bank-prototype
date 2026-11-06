package bank.pbp.orchestrator.payments;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the payment saga on Temporal's in-memory test server with fake activities. */
class PaymentWorkflowTest {

    private TestWorkflowEnvironment env;
    private FakeActivities fake;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        fake = new FakeActivities();
        Worker worker = env.newWorker(TaskQueues.PAYMENTS);
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void sameBankPaymentCompletes() {
        assertThat(run(command("500.00", "INTERNAL"))).isEqualTo("COMPLETED");
        assertThat(fake.transitions).containsExactly(
                "INITIATED->CHECKS_PASSED", "CHECKS_PASSED->SUBMITTED", "SUBMITTED->COMPLETED");
    }

    @Test
    void insufficientFundsIsRejected() {
        fake.refuseTransfer = true;
        assertThat(run(command("500.00", "INTERNAL"))).isEqualTo("REJECTED");
        assertThat(fake.transitions).last().isEqualTo("SUBMITTED->REJECTED");
    }

    @Test
    void overLimitIsRejectedBeforeSubmission() {
        assertThat(run(command("30000.00", "INTERNAL"))).isEqualTo("REJECTED");
        assertThat(fake.transitions).containsExactly("INITIATED->REJECTED");
    }

    @Test
    void outboundRailsAreNotInPhaseZero() {
        assertThat(run(command("20.00", "FPS"))).isEqualTo("REJECTED");
        assertThat(fake.transitions).last().isEqualTo("CHECKS_PASSED->REJECTED");
    }

    private String run(PaymentCommand command) {
        PaymentWorkflow workflow = env.getWorkflowClient().newWorkflowStub(PaymentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TaskQueues.PAYMENTS)
                        .setWorkflowId("payment-" + command.paymentId()).build());
        return workflow.run(command);
    }

    private static PaymentCommand command(String amount, String type) {
        return new PaymentCommand("0192f1c5-0000-7000-8000-000000000001", "0192f1c4-0000-7000-8000-000000000001",
                "COR-PAY-7K2M9QXA4B1D", "ACC-5501", "Ravi Kumar", "200001", "41550198", amount, "GBP",
                "Rent share", type, "key-1", "2026-11-06T10:00:00Z");
    }

    /** Stands in for the database, Kafka and Essence adapter. */
    static class FakeActivities implements PaymentActivities {

        final List<String> transitions = Collections.synchronizedList(new ArrayList<>());
        volatile boolean refuseTransfer;

        @Override
        public void createPayment(PaymentCommand command) {
        }

        @Override
        public RiskResult riskCheck(PaymentCommand command) {
            boolean ok = new java.math.BigDecimal(command.amount()).compareTo(new java.math.BigDecimal("25000.00")) <= 0;
            return ok ? new RiskResult(true, null, null) : new RiskResult(false, "PAY-LIMIT-001", "over limit");
        }

        @Override
        public void transition(PaymentCommand command, String from, String to, String reason) {
            transitions.add(from + "->" + to);
        }

        @Override
        public String postInternalTransfer(PaymentCommand command) {
            if (refuseTransfer) {
                throw ApplicationFailure.newNonRetryableFailure("Available balance is below the amount", "PAY-FUNDS-001");
            }
            return "PST-TEST0001";
        }
    }
}
