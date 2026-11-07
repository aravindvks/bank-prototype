package bank.pbp.orchestrator.temporal;

import bank.pbp.orchestrator.cards.CardActivitiesImpl;
import bank.pbp.orchestrator.cards.CardControlsWorkflow;
import bank.pbp.orchestrator.compliance.ComplianceActivitiesImpl;
import bank.pbp.orchestrator.compliance.ComplianceWorkflow;
import bank.pbp.orchestrator.directdebits.DirectDebitActivitiesImpl;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows;
import bank.pbp.orchestrator.onboarding.OnboardingActivitiesImpl;
import bank.pbp.orchestrator.onboarding.OnboardingWorkflowImpl;
import bank.pbp.orchestrator.payments.InboundActivitiesImpl;
import bank.pbp.orchestrator.payments.InboundPaymentWorkflow;
import bank.pbp.orchestrator.payments.PaymentActivitiesImpl;
import bank.pbp.orchestrator.payments.PaymentWorkflowImpl;
import bank.pbp.orchestrator.schedules.ScheduleActivitiesImpl;
import bank.pbp.orchestrator.schedules.ScheduleWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal client and workers. Namespace pbp-&lt;env&gt;; one worker per task queue. */
@Configuration
public class TemporalConfig {

    @Bean
    WorkflowServiceStubs workflowServiceStubs(@Value("${temporal.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, @Value("${temporal.namespace}") String namespace) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    @Bean
    WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    TemporalWorkers temporalWorkers(WorkerFactory factory, PaymentActivitiesImpl paymentActivities,
                                    OnboardingActivitiesImpl onboardingActivities,
                                    ComplianceActivitiesImpl complianceActivities,
                                    InboundActivitiesImpl inboundActivities,
                                    CardActivitiesImpl cardActivities,
                                    ScheduleActivitiesImpl scheduleActivities,
                                    DirectDebitActivitiesImpl directDebitActivities) {
        return new TemporalWorkers(factory, paymentActivities, onboardingActivities, complianceActivities,
                inboundActivities, cardActivities, scheduleActivities, directDebitActivities);
    }

    /** Starts polling after the context is ready and stops cleanly on shutdown. */
    static final class TemporalWorkers implements SmartLifecycle {

        private static final Logger log = LoggerFactory.getLogger(TemporalWorkers.class);

        private final WorkerFactory factory;
        private final PaymentActivitiesImpl paymentActivities;
        private final OnboardingActivitiesImpl onboardingActivities;
        private final ComplianceActivitiesImpl complianceActivities;
        private final InboundActivitiesImpl inboundActivities;
        private final CardActivitiesImpl cardActivities;
        private final ScheduleActivitiesImpl scheduleActivities;
        private final DirectDebitActivitiesImpl directDebitActivities;
        private volatile boolean running;

        TemporalWorkers(WorkerFactory factory, PaymentActivitiesImpl paymentActivities,
                        OnboardingActivitiesImpl onboardingActivities, ComplianceActivitiesImpl complianceActivities,
                        InboundActivitiesImpl inboundActivities, CardActivitiesImpl cardActivities,
                        ScheduleActivitiesImpl scheduleActivities, DirectDebitActivitiesImpl directDebitActivities) {
            this.factory = factory;
            this.paymentActivities = paymentActivities;
            this.onboardingActivities = onboardingActivities;
            this.complianceActivities = complianceActivities;
            this.inboundActivities = inboundActivities;
            this.cardActivities = cardActivities;
            this.scheduleActivities = scheduleActivities;
            this.directDebitActivities = directDebitActivities;
        }

        @Override
        public void start() {
            Worker payments = factory.newWorker(TaskQueues.PAYMENTS);
            payments.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class, InboundPaymentWorkflow.Impl.class,
                    ScheduleWorkflow.Impl.class, DirectDebitWorkflows.MandateImpl.class,
                    DirectDebitWorkflows.CollectionImpl.class, DirectDebitWorkflows.ClaimImpl.class);
            payments.registerActivitiesImplementations(paymentActivities, inboundActivities, scheduleActivities,
                    directDebitActivities);
            Worker onboarding = factory.newWorker(TaskQueues.ONBOARDING);
            onboarding.registerWorkflowImplementationTypes(OnboardingWorkflowImpl.class);
            onboarding.registerActivitiesImplementations(onboardingActivities);
            Worker compliance = factory.newWorker(TaskQueues.COMPLIANCE);
            compliance.registerWorkflowImplementationTypes(ComplianceWorkflow.Impl.class);
            compliance.registerActivitiesImplementations(complianceActivities);
            Worker cards = factory.newWorker(TaskQueues.CARDS);
            cards.registerWorkflowImplementationTypes(CardControlsWorkflow.Impl.class);
            cards.registerActivitiesImplementations(cardActivities);
            factory.start();
            running = true;
            log.info("temporal.workers.started queues=[{}, {}, {}]", TaskQueues.PAYMENTS, TaskQueues.ONBOARDING,
                    TaskQueues.COMPLIANCE);
        }

        @Override
        public void stop() {
            factory.shutdown();
            factory.awaitTermination(10, TimeUnit.SECONDS);
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
