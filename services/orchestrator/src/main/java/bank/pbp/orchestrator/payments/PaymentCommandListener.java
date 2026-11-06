package bank.pbp.orchestrator.payments;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns payment.create commands into payment workflows. The workflow ID is the business key,
 * so a redelivered command finds the running workflow instead of starting a second one.
 */
@Component
public class PaymentCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

    private final WorkflowClient temporal;
    private final EventPublisher events;
    private final String bankSortCode;

    public PaymentCommandListener(WorkflowClient temporal, EventPublisher events,
                                  @Value("${pbp.bank.sort-code}") String bankSortCode) {
        this.temporal = temporal;
        this.events = events;
        this.bankSortCode = bankSortCode;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "orchestrator-payments")
    public void onCommand(String message) {
        EventEnvelope event = events.read(message);
        if (!"payment.create".equals(event.type())) {
            log.info("command.ignored type={}", event.type());
            return;
        }
        PaymentCommand command = toCommand(event);
        PaymentWorkflow workflow = temporal.newWorkflowStub(PaymentWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.PAYMENTS)
                .setWorkflowId("payment-" + command.paymentId())
                .build());
        try {
            WorkflowClient.start(workflow::run, command);
            log.info("workflow.started workflowId=payment-{} type={}", command.paymentId(), command.paymentType());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.info("workflow.duplicate workflowId=payment-{} (command redelivered)", command.paymentId());
        }
    }

    @SuppressWarnings("unchecked")
    PaymentCommand toCommand(EventEnvelope event) {
        Map<String, Object> d = event.data();
        Map<String, Object> creditor = (Map<String, Object>) d.get("creditor");
        Map<String, Object> amount = (Map<String, Object>) d.get("amount");
        String sortCode = str(creditor.get("sortCode"));
        return new PaymentCommand(
                str(d.get("paymentId")),
                event.customerId(),
                event.correlationId(),
                str(d.get("debtorAccount")),
                str(creditor.get("name")),
                sortCode,
                str(creditor.get("accountNumber")),
                str(amount.get("value")),
                str(amount.get("currency")),
                str(d.get("remittanceInfo")),
                bankSortCode.equals(sortCode) ? "INTERNAL" : "FPS",
                str(d.get("idempotencyKey")),
                str(d.get("initiatedAt")));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
